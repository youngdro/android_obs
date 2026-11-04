package app.livecomposer.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.CountDownLatch
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 合成器：独立 GL 线程，按固定帧率把「摄像头 + 网页 + 图片/文字」合成到一张离屏 FBO（输出画布），
 * 再把这张画布分别画到 ① 编码器输入 Surface ② 屏幕预览 Surface。
 * 因为编码与预览来自同一张 FBO，接收端画面与本地预览完全一致；编辑框等 UI 只存在于 Android View 层，不会进入视频。
 */
class Compositor(canvasWidth: Int, canvasHeight: Int, fps: Int) {

    /** 一个要画的图层（快照，线程安全地整体替换）。rect 为画布像素坐标，左上角为原点。 */
    data class RenderLayer(
        val id: String,
        val kind: Kind,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val opacity: Float = 1f,
    ) {
        enum class Kind { EXTERNAL, BITMAP }
    }

    @Volatile var canvasWidth = canvasWidth; private set
    @Volatile var canvasHeight = canvasHeight; private set
    @Volatile var fps = fps; private set

    private val thread = HandlerThread("compositor").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var egl: EglCore
    private lateinit var oes: QuadRenderer
    private lateinit var tex2d: QuadRenderer

    // 摄像头
    private var cameraTex = 0
    private var cameraSt: SurfaceTexture? = null
    private val cameraMatrix = FloatArray(16)
    @Volatile private var cameraFrameAvailable = false
    @Volatile private var cameraHasFrame = false
    private var cameraBufW = 1920
    private var cameraBufH = 1080
    private var cameraSensorOrientation = 90
    private var cameraRotationDeg = 0

    // 外部纹理源（网页图层）
    private class External(val tex: Int, val st: SurfaceTexture, val surface: Surface) {
        val matrix = FloatArray(16)
        @Volatile var available = false
        var hasFrame = false
    }
    private val externals = HashMap<String, External>()

    // 位图源（图片 / 文字图层）
    private class BitmapTex(val tex: Int, var w: Int, var h: Int)
    private val bitmaps = HashMap<String, BitmapTex>()

    @Volatile private var scene: List<RenderLayer> = emptyList()

    /**
     * 音画同步测试信号：画面左上角 1/8 区域平时为黑块，每到整秒（MONOTONIC 时钟）的第一帧变白；
     * 音频侧在同一整秒时刻插入 1kHz 提示音（见 AudioPipeline.syncTest）。接收端用 tools/av_sync.py 测量偏差。
     */
    @Volatile var syncTest = false
    private var lastFlashSec = -1L
    private var whiteTex = 0
    private var blackTex = 0

    // 输出
    private var fbo = 0
    private var fboTex = 0
    private var previewSurface: EGLSurface? = null
    private var previewW = 0
    private var previewH = 0
    private var encoderSurface: EGLSurface? = null

    // 帧时钟
    private var running = false
    private var clockStartNs = 0L
    private var frameIndex = 0L
    private var lastPtsNs = 0L

    // 统计
    @Volatile var renderedFrames = 0L; private set
    @Volatile var encodedFrames = 0L; private set
    @Volatile var lateFrames = 0L; private set
    @Volatile var lastError: String? = null; private set

    fun start(onCameraSurface: (SurfaceTexture) -> Unit) {
        handler.post {
            egl = EglCore()
            oes = QuadRenderer(external = true)
            tex2d = QuadRenderer(external = false)
            cameraTex = QuadRenderer.createTexture(true)
            val st = SurfaceTexture(cameraTex)
            st.setOnFrameAvailableListener({ cameraFrameAvailable = true }, handler)
            cameraSt = st
            createFbo()
            whiteTex = solidTexture(0xFFFFFFFF.toInt())
            blackTex = solidTexture(0xFF000000.toInt())
            running = true
            clockStartNs = System.nanoTime()
            frameIndex = 0
            scheduleNext()
            onCameraSurface(st)
        }
    }

    fun release() {
        val latch = CountDownLatch(1)
        handler.post {
            running = false
            try {
                previewSurface?.let { egl.releaseSurface(it) }
                encoderSurface?.let { egl.releaseSurface(it) }
                externals.values.forEach { it.surface.release(); it.st.release() }
                externals.clear()
                cameraSt?.release()
                egl.makeNothingCurrent()
                oes.release(); tex2d.release()
                egl.release()
            } catch (e: Exception) {
                Log.w(TAG, "release", e)
            }
            latch.countDown()
        }
        latch.await()
        thread.quitSafely()
    }

    /** 开播前修改画布尺寸 / 帧率（推流中锁定）。 */
    fun configure(width: Int, height: Int, newFps: Int) = handler.post {
        canvasWidth = width; canvasHeight = height; fps = newFps
        if (::egl.isInitialized) {
            deleteFbo(); createFbo()
            clockStartNs = System.nanoTime(); frameIndex = 0
        }
    }

    fun setCameraGeometry(bufferW: Int, bufferH: Int, sensorOrientation: Int, rotationDeg: Int) = handler.post {
        cameraBufW = bufferW; cameraBufH = bufferH
        cameraSensorOrientation = sensorOrientation; cameraRotationDeg = rotationDeg
        cameraSt?.setDefaultBufferSize(bufferW, bufferH)
    }

    fun setScene(layers: List<RenderLayer>) { scene = layers }

    /** 为网页图层创建一个可被 Canvas 绘制的 Surface（内容带 Alpha）。 */
    fun createExternalSurface(id: String, width: Int, height: Int, cb: (Surface) -> Unit) = handler.post {
        externals.remove(id)?.let { it.surface.release(); it.st.release() }
        val tex = QuadRenderer.createTexture(true)
        val st = SurfaceTexture(tex)
        st.setDefaultBufferSize(width, height)
        val ext = External(tex, st, Surface(st))
        st.setOnFrameAvailableListener({ ext.available = true }, handler)
        externals[id] = ext
        cb(ext.surface)
    }

    fun releaseExternalSurface(id: String) = handler.post {
        externals.remove(id)?.let {
            it.surface.release(); it.st.release()
            GLES20.glDeleteTextures(1, intArrayOf(it.tex), 0)
        }
    }

    /** 上传位图（调用方之后不要再修改该 Bitmap）。 */
    fun setBitmap(id: String, bitmap: Bitmap) = handler.post {
        val b = bitmaps.getOrPut(id) { BitmapTex(QuadRenderer.createTexture(false), 0, 0) }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, b.tex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0) // Android Bitmap 为预乘 Alpha
        b.w = bitmap.width; b.h = bitmap.height
    }

    fun removeBitmap(id: String) = handler.post {
        bitmaps.remove(id)?.let { GLES20.glDeleteTextures(1, intArrayOf(it.tex), 0) }
    }

    fun setPreviewSurface(surface: Surface?, width: Int, height: Int) {
        val latch = CountDownLatch(1)
        handler.post {
            previewSurface?.let { egl.makeNothingCurrent(); egl.releaseSurface(it) }
            previewSurface = null
            if (surface != null && surface.isValid) {
                try {
                    previewSurface = egl.createWindowSurface(surface)
                    previewW = width; previewH = height
                } catch (e: Exception) {
                    Log.w(TAG, "preview surface", e)
                }
            }
            latch.countDown()
        }
        latch.await()
    }

    fun setEncoderSurface(surface: Surface?) {
        val latch = CountDownLatch(1)
        handler.post {
            encoderSurface?.let { egl.makeNothingCurrent(); egl.releaseSurface(it) }
            encoderSurface = null
            if (surface != null) encoderSurface = egl.createWindowSurface(surface)
            encodedFrames = 0
            latch.countDown()
        }
        latch.await()
    }

    /** 画布坐标（像素）→ 摄像头自然方向图像的归一化坐标 (u 向右, v 向下)，用于点击对焦。 */
    fun canvasToCameraUv(px: Float, py: Float): Pair<Float, Float>? {
        val (nw, nh) = naturalCameraSize()
        val a = Math.toRadians(cameraRotationDeg.toDouble())
        val rotW = if (cameraRotationDeg % 180 == 0) nw else nh
        val rotH = if (cameraRotationDeg % 180 == 0) nh else nw
        val s = max(canvasWidth / rotW, canvasHeight / rotH)
        // 画布像素 → 以中心为原点、y 向上
        val cx = (px - canvasWidth / 2f) / s
        val cy = (canvasHeight / 2f - py) / s
        // 逆旋转
        val ix = (cx * cos(-a) - cy * sin(-a)).toFloat()
        val iy = (cx * sin(-a) + cy * cos(-a)).toFloat()
        val u = ix / nw + 0.5f
        val v = 0.5f - iy / nh
        if (u !in 0f..1f || v !in 0f..1f) return null
        return u to v
    }

    // ---------------------------------------------------------------- 内部

    private fun naturalCameraSize(): Pair<Float, Float> =
        if (cameraSensorOrientation % 180 == 90) cameraBufH.toFloat() to cameraBufW.toFloat()
        else cameraBufW.toFloat() to cameraBufH.toFloat()

    private fun createFbo() {
        fboTex = QuadRenderer.createTexture(false)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, canvasWidth, canvasHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) lastError = "FBO 不完整: 0x${Integer.toHexString(status)}"
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun solidTexture(argb: Int): Int {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(argb)
        val t = QuadRenderer.createTexture(false)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return t
    }

    private fun deleteFbo() {
        GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
    }

    private fun scheduleNext() {
        if (!running) return
        frameIndex++
        val periodNs = 1_000_000_000L / fps
        val targetNs = clockStartNs + frameIndex * periodNs
        val delayMs = (targetNs - System.nanoTime()) / 1_000_000
        if (delayMs < -periodNs / 1_000_000 * 3) {
            // 落后太多（例如 GPU 阻塞）：重置时钟，记一次迟到
            lateFrames++
            clockStartNs = System.nanoTime(); frameIndex = 0
            handler.post { tick() }
        } else {
            handler.postAtTime({ tick() }, SystemClock.uptimeMillis() + max(0L, delayMs))
        }
    }

    private fun tick() {
        if (!running) return
        try {
            renderFrame()
        } catch (e: Exception) {
            lastError = e.message
            Log.e(TAG, "render", e)
        }
        scheduleNext()
    }

    private fun renderFrame() {
        val now = System.nanoTime()
        val pts = if (now <= lastPtsNs) lastPtsNs + 1000 else now
        lastPtsNs = pts
        egl.makeNothingCurrent()
        if (cameraFrameAvailable) {
            cameraFrameAvailable = false
            cameraSt?.updateTexImage()
            cameraSt?.getTransformMatrix(cameraMatrix)
            cameraHasFrame = true
        }
        for (e in externals.values) {
            if (e.available) {
                e.available = false
                e.st.updateTexImage()
                e.st.getTransformMatrix(e.matrix)
                e.hasFrame = true
            }
        }

        // ---- 1. 合成到 FBO ----
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, canvasWidth, canvasHeight)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (cameraHasFrame) oes.draw(cameraTex, cameraMatrix, cameraCorners(), 1f)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // 预乘 Alpha 混合
        for (l in scene) {
            val corners = rectCorners(l.x, l.y, l.w, l.h)
            when (l.kind) {
                RenderLayer.Kind.EXTERNAL -> {
                    val e = externals[l.id] ?: continue
                    if (e.hasFrame) oes.draw(e.tex, e.matrix, corners, l.opacity)
                }
                RenderLayer.Kind.BITMAP -> {
                    val b = bitmaps[l.id] ?: continue
                    tex2d.draw(b.tex, QuadRenderer.FLIP_V, corners, l.opacity)
                }
            }
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        if (syncTest) {
            val sec = pts / 1_000_000_000L
            val flash = sec != lastFlashSec
            lastFlashSec = sec
            tex2d.draw(if (flash) whiteTex else blackTex, QuadRenderer.IDENTITY,
                rectCorners(0f, 0f, canvasWidth / 8f, canvasHeight / 8f), 1f)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        renderedFrames++

        // ---- 2. 输出到编码器 ----
        encoderSurface?.let { s ->
            egl.makeCurrent(s)
            GLES20.glViewport(0, 0, canvasWidth, canvasHeight)
            tex2d.draw(fboTex, QuadRenderer.IDENTITY, FULL, 1f)
            egl.setPresentationTime(s, pts)
            if (egl.swap(s)) encodedFrames++
        }
        // ---- 3. 输出到预览 ----
        previewSurface?.let { s ->
            egl.makeCurrent(s)
            GLES20.glViewport(0, 0, previewW, previewH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            tex2d.draw(fboTex, QuadRenderer.IDENTITY, FULL, 1f)
            egl.swap(s)
        }
    }

    /** 摄像头画面：按自然方向旋转 cameraRotationDeg（逆时针），再等比铺满画布（居中裁切）。 */
    private fun cameraCorners(): FloatArray {
        val (nw, nh) = naturalCameraSize()
        val a = Math.toRadians(cameraRotationDeg.toDouble())
        val rotW = if (cameraRotationDeg % 180 == 0) nw else nh
        val rotH = if (cameraRotationDeg % 180 == 0) nh else nw
        val s = max(canvasWidth / rotW, canvasHeight / rotH)
        val out = FloatArray(8)
        val pts = floatArrayOf(-nw / 2, -nh / 2, nw / 2, -nh / 2, -nw / 2, nh / 2, nw / 2, nh / 2)
        for (i in 0 until 4) {
            val x = pts[i * 2]; val y = pts[i * 2 + 1]
            val rx = (x * cos(a) - y * sin(a)) * s
            val ry = (x * sin(a) + y * cos(a)) * s
            out[i * 2] = (rx * 2 / canvasWidth).toFloat()
            out[i * 2 + 1] = (ry * 2 / canvasHeight).toFloat()
        }
        return out
    }

    private fun rectCorners(x: Float, y: Float, w: Float, h: Float): FloatArray {
        val l = x / canvasWidth * 2 - 1
        val r = (x + w) / canvasWidth * 2 - 1
        val t = 1 - y / canvasHeight * 2
        val b = 1 - (y + h) / canvasHeight * 2
        return floatArrayOf(l, b, r, b, l, t, r, t)
    }

    companion object {
        private const val TAG = "Compositor"
        private val FULL = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    }
}
