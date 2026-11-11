package app.livecomposer.source

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.abs

/** Camera2 后置主摄采集：连续自动对焦、点击对焦、变焦，输出到合成器提供的 SurfaceTexture。 */
class CameraSource(context: Context, private val listener: Listener) {

    interface Listener {
        fun onCameraState(state: State, message: String?)
    }

    enum class State(val label: String) { CLOSED("未打开"), OPENING("打开中"), RUNNING("运行中"), ERROR("错误"), IN_USE("被占用") }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    val cameraId: String? = manager.cameraIdList.firstOrNull {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }
    private val chars: CameraCharacteristics? = cameraId?.let { manager.getCameraCharacteristics(it) }
    val sensorOrientation: Int = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    private val activeArray: Rect = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
    private val maxAfRegions = chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
    private val maxAeRegions = chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0

    val zoomRange: Range<Float> = run {
        if (Build.VERSION.SDK_INT >= 30) chars?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { return@run it }
        val maxDigital = chars?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        Range(1f, maxDigital)
    }

    /** 设备支持的摄像头输出尺寸（用于展示可选分辨率）。 */
    val outputSizes: List<Size> = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(SurfaceTexture::class.java)?.toList()?.sortedByDescending { it.width * it.height } ?: emptyList()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var builder: CaptureRequest.Builder? = null
    private var zoom = 1f
    private var wantOpen = false

    @Volatile var state = State.CLOSED; private set

    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(id: String) {
            // 被其他应用占用后恢复可用：自动重新打开
            if (id == cameraId && wantOpen && (state == State.IN_USE || state == State.ERROR)) handler.post { openInternal() }
        }
    }

    init {
        manager.registerAvailabilityCallback(availability, handler)
    }

    /** 选择与画布匹配的采集尺寸（长边 ≥ 目标长边、宽高比最接近 16:9）。 */
    fun chooseSize(canvasLong: Int, canvasShort: Int): Size {
        val target = canvasLong.toFloat() / canvasShort
        val candidates = outputSizes.filter { maxOf(it.width, it.height) <= 1920 }
        return candidates
            .filter { maxOf(it.width, it.height) >= canvasLong && minOf(it.width, it.height) >= canvasShort }
            .minWithOrNull(compareBy<Size> { abs(maxOf(it.width, it.height).toFloat() / minOf(it.width, it.height) - target) }
                .thenBy { it.width * it.height })
            ?: candidates.firstOrNull() ?: Size(1920, 1080)
    }

    fun open(target: SurfaceTexture, size: Size) {
        handler.post {
            target.setDefaultBufferSize(size.width, size.height)
            surface?.release()
            surface = Surface(target)
            wantOpen = true
            openInternal()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openInternal() {
        val id = cameraId ?: run { setState(State.ERROR, "没有找到后置摄像头"); return }
        closeInternal()
        setState(State.OPENING, null)
        try {
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    startSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); device = null; session = null
                    setState(State.IN_USE, "摄像头被其他应用占用或已断开，释放后会自动恢复")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); device = null; session = null
                    val msg = when (error) {
                        ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE -> "摄像头正被其他应用使用"
                        ERROR_CAMERA_DISABLED -> "摄像头被系统策略禁用"
                        ERROR_CAMERA_DEVICE -> "摄像头设备错误"
                        ERROR_CAMERA_SERVICE -> "摄像头服务错误"
                        else -> "摄像头错误 $error"
                    }
                    setState(if (error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE) State.IN_USE else State.ERROR, msg)
                }
            })
        } catch (e: SecurityException) {
            setState(State.ERROR, "没有摄像头权限")
        } catch (e: CameraAccessException) {
            setState(State.IN_USE, "无法打开摄像头：${e.message}")
        }
    }

    private fun startSession(camera: CameraDevice) {
        val s = surface ?: return
        val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(s)), executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    session = sess
                    try {
                        val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        b.addTarget(s)
                        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        chooseFpsRange()?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        builder = b
                        applyZoom(b)
                        sess.setRepeatingRequest(b.build(), null, handler)
                        setState(State.RUNNING, null)
                    } catch (e: Exception) {
                        setState(State.ERROR, "启动预览失败：${e.message}")
                    }
                }

                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    setState(State.ERROR, "摄像头会话配置失败")
                }
            })
        try {
            camera.createCaptureSession(cfg)
        } catch (e: Exception) {
            setState(State.ERROR, "摄像头会话创建失败：${e.message}")
        }
    }

    private fun chooseFpsRange(): Range<Int>? {
        val ranges = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.firstOrNull { it.lower == 30 && it.upper == 30 } ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
    }

    fun setZoom(ratio: Float) = handler.post {
        zoom = ratio.coerceIn(zoomRange.lower, zoomRange.upper)
        val b = builder ?: return@post
        applyZoom(b)
        try { session?.setRepeatingRequest(b.build(), null, handler) } catch (e: Exception) { Log.w(TAG, "zoom", e) }
    }

    val currentZoom: Float get() = zoom

    private fun applyZoom(b: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            val cw = (activeArray.width() / zoom).toInt()
            val ch = (activeArray.height() / zoom).toInt()
            val l = activeArray.left + (activeArray.width() - cw) / 2
            val t = activeArray.top + (activeArray.height() - ch) / 2
            b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + cw, t + ch))
        }
    }

    /**
     * 点击对焦。u/v 为“自然方向（竖持手机）”图像的归一化坐标（u 向右、v 向下）。
     * 需换算到传感器坐标（传感器通常横向安装，sensorOrientation=90）。
     */
    fun focusAt(u: Float, v: Float) = handler.post {
        val sess = session ?: return@post
        val b = builder ?: return@post
        // 自然方向 → 传感器方向：顺时针旋转 sensorOrientation 的逆
        val (sx, sy) = when (sensorOrientation) {
            90 -> v to (1 - u)
            180 -> (1 - u) to (1 - v)
            270 -> (1 - v) to u
            else -> u to v
        }
        // 变焦后可见区域只是传感器中心的一部分
        val visW = activeArray.width() / zoom
        val visH = activeArray.height() / zoom
        val cx = activeArray.centerX() + (sx - 0.5f) * visW
        val cy = activeArray.centerY() + (sy - 0.5f) * visH
        val half = (minOf(activeArray.width(), activeArray.height()) * 0.08f / zoom).toInt().coerceAtLeast(50)
        val rect = Rect((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        rect.intersect(activeArray)
        val region = arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1))
        try {
            sess.stopRepeating()
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            sess.capture(b.build(), null, handler)
            if (maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, region)
            if (maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, region)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            sess.capture(b.build(), null, handler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            sess.setRepeatingRequest(b.build(), null, handler)
            // 5 秒后恢复连续对焦
            handler.removeCallbacksAndMessages(RESUME_TOKEN)
            handler.postDelayed({ resumeContinuousAf() }, RESUME_TOKEN, 5000)
        } catch (e: Exception) {
            Log.w(TAG, "focus", e)
        }
    }

    private fun resumeContinuousAf() {
        val sess = session ?: return
        val b = builder ?: return
        try {
            b.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            b.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            sess.setRepeatingRequest(b.build(), null, handler)
        } catch (e: Exception) {
            Log.w(TAG, "resume af", e)
        }
    }

    fun retry() = handler.post { if (wantOpen) openInternal() }

    fun close() = handler.post {
        wantOpen = false
        closeInternal()
        setState(State.CLOSED, null)
    }

    private fun closeInternal() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        session = null; device = null; builder = null
    }

    fun release() {
        manager.unregisterAvailabilityCallback(availability)
        close()
        handler.post { surface?.release(); surface = null }
        thread.quitSafely()
    }

    private fun setState(s: State, msg: String?) {
        state = s
        listener.onCameraState(s, msg)
    }

    companion object {
        private const val TAG = "CameraSource"
        private val RESUME_TOKEN = Any()
    }
}
