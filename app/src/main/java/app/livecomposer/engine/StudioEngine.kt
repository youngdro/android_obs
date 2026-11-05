package app.livecomposer.engine

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import app.livecomposer.core.AppConfig
import app.livecomposer.core.CanvasOrientation
import app.livecomposer.core.Layer
import app.livecomposer.core.LayerType
import app.livecomposer.core.Resolution
import app.livecomposer.core.Scene
import app.livecomposer.core.SceneStore
import app.livecomposer.core.sceneLayers
import app.livecomposer.core.toSceneData
import app.livecomposer.encode.AudioPipeline
import app.livecomposer.encode.VideoEncoder
import app.livecomposer.gl.Compositor
import app.livecomposer.rtmp.RtmpPublisher
import app.livecomposer.rtmp.RtmpUrl
import app.livecomposer.rtmp.RtmpUrlException
import app.livecomposer.source.CameraSource
import app.livecomposer.source.PlayerSource
import app.livecomposer.source.WebOverlaySource
import app.livecomposer.stream.DeviceMetrics
import app.livecomposer.stream.RunLogger
import app.livecomposer.stream.StreamController
import kotlin.math.abs

/** 场景列表项（界面用）。 */
data class SceneInfo(
    val id: String, val name: String, val savedAt: Long,
    val orientation: CanvasOrientation, val resolution: Resolution, val fps: Int,
    val layerTypes: List<LayerType>,
)

/** 媒体图层播放状态。 */
/** 第二路（纯摄像头）推流的状态与设置。 */
data class CamStreamState(
    val active: Boolean,
    val stats: StreamController.Stats?,
    val message: String?,
    val url: String,
    val audio: Boolean,
    val resolution: Resolution,
    val bitrateKbps: Int,
)

data class MediaStatus(val state: PlayerSource.State, val message: String?, val videoW: Int, val videoH: Int)

/** 直播状态（界面用）。 */
enum class LiveState { IDLE, CONNECTING, LIVE, RECONNECTING, FAILED }

/** 引擎对外的只读快照。界面层只读它，所有修改都通过 StudioEngine 的方法。 */
data class EngineState(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val orientation: CanvasOrientation,
    val resolution: Resolution,
    val fps: Int,
    val videoBitrateKbps: Int,
    val keyFrameIntervalSec: Int,
    val layers: List<Layer>,
    val live: LiveState,
    val durationSec: Long,
    val outFps: Float,
    val bitrateKbps: Float,
    val droppedFrames: Long,
    val reconnects: Int,
    val streamMessage: String?,
    val cameraMessage: String?,
    val micMessage: String?,
    val micLevel: Float,
    val muted: Boolean,
    val webState: WebOverlaySource.LoadState,
    val webMessage: String?,
    val webViewport: String?,
    val renderFps: Int,
    val webFps: Int,
    val webCaptureMs: Double,
    val metrics: DeviceMetrics.Sample?,
    val zoom: Float,
    val zoomMin: Float,
    val zoomMax: Float,
    val syncTest: Boolean,
    val noiseSuppression: Boolean,
    /** 网页声音接管情况。 */
    val webAudio: String?,
    val powerSave: Boolean,
    val monitorLocal: Boolean,
    /** 第二路（纯摄像头）推流。 */
    val cam: CamStreamState,
    /** 网页采集帧率，0 = 跟随输出。 */
    val webCaptureFps: Int,
    val webGpu: Boolean,
    val allowCleartext: Boolean,
    val cameraRotationFix: Int,
    val useFullUrl: Boolean,
    val fullUrl: String,
    val serverUrl: String,
    val streamKey: String,
    val availableResolutions: List<Resolution>,
    val cameraSizes: String,
    val notice: String?,
    /** 媒体图层（拉流 / 本地视频）的播放状态，按图层 id。 */
    val media: Map<String, MediaStatus> = emptyMap(),
    /** 各有声图层的电平 0..1，按图层 id（摄像头图层 = 麦克风）。 */
    val audioLevels: Map<String, Float> = emptyMap(),
    val scenes: List<SceneInfo> = emptyList(),
    /** 最近一次切换到或保存的场景（之后改了画面也仍显示它，便于“更新为当前”）。 */
    val activeSceneId: String? = null,
) {
    val isStreaming: Boolean get() = live != LiveState.IDLE && live != LiveState.FAILED
}

/**
 * 直播引擎：持有摄像头、网页、合成器、编码与推流，和界面完全解耦。
 * 所有方法在主线程调用；状态变化通过 [onChanged] 通知（主线程）。
 */
class StudioEngine(private val activity: Activity, private val webParent: ViewGroup) {

    var onChanged: (() -> Unit)? = null

    val config: AppConfig = AppConfig.load(activity)
    private val sceneStore = SceneStore(activity)
    private val main = Handler(Looper.getMainLooper())
    private val metricsSampler = DeviceMetrics(activity)
    private val runLogger = RunLogger(activity)

    private var ready = false
    private lateinit var compositor: Compositor
    private lateinit var camera: CameraSource
    private lateinit var audio: AudioPipeline
    private lateinit var stream: StreamController
    private var web: WebOverlaySource? = null
    private var webOutputVisible = false
    private var cameraTexture: SurfaceTexture? = null
    private var started = false

    // 运行状态
    private var stats: StreamController.Stats? = null
    private var streamActive = false
    private var streamEndMessage: String? = null
    private var cameraMessage: String? = null
    private var micMessage: String? = null
    private var micLevel = 0f
    private var webState = WebOverlaySource.LoadState.IDLE
    private var webMessage: String? = null
    private var webViewport: String? = null
    private var renderFps = 0
    private var webFps = 0
    private var metrics: DeviceMetrics.Sample? = null
    private var syncTest = false
    private var webAudio: String? = null
    private lateinit var stream2: StreamController
    private var stream2Active = false
    private var stats2: StreamController.Stats? = null
    private var stream2EndMessage: String? = null
    private var notice: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val bitmapKeys = HashMap<String, String>()
    private val players = HashMap<String, PlayerSource>()
    private val mediaStatus = HashMap<String, MediaStatus>()
    /** 新加的媒体图层：拿到画面尺寸后把图层框调成画面比例。 */
    private val autoSize = HashSet<String>()

    private var cachedResolutions: List<Resolution>? = null

    // ------------------------------------------------------------------ 状态快照

    fun snapshot(): EngineState {
        val st = stats
        val live = when {
            !streamActive -> if (streamEndMessage != null && stats == null && lastFailed) LiveState.FAILED else LiveState.IDLE
            st == null -> LiveState.CONNECTING
            else -> when (st.state) {
                RtmpPublisher.State.LIVE -> LiveState.LIVE
                RtmpPublisher.State.RECONNECTING -> LiveState.RECONNECTING
                RtmpPublisher.State.FAILED -> LiveState.FAILED
                else -> LiveState.CONNECTING
            }
        }
        return EngineState(
            canvasWidth = config.canvasWidth, canvasHeight = config.canvasHeight,
            orientation = config.orientation, resolution = config.resolution, fps = config.fps,
            videoBitrateKbps = config.videoBitrateKbps, keyFrameIntervalSec = config.keyFrameIntervalSec,
            layers = config.layers.map { it.copy() },
            live = live,
            durationSec = st?.durationSec ?: 0, outFps = st?.outputFps ?: 0f, bitrateKbps = st?.bitrateKbps ?: 0f,
            droppedFrames = st?.droppedFrames ?: 0, reconnects = st?.reconnects ?: 0,
            streamMessage = if (streamActive) st?.message else streamEndMessage,
            cameraMessage = cameraMessage, micMessage = micMessage, micLevel = micLevel, muted = config.micMuted,
            webState = webState, webMessage = webMessage, webViewport = webViewport,
            renderFps = renderFps, webFps = webFps, webCaptureMs = web?.lastCaptureMs ?: 0.0, metrics = metrics,
            zoom = config.zoom,
            zoomMin = if (ready) camera.zoomRange.lower else 1f, zoomMax = if (ready) camera.zoomRange.upper else 1f,
            syncTest = syncTest, noiseSuppression = config.noiseSuppression, webAudio = webAudio,
            powerSave = config.powerSave, monitorLocal = config.monitorLocal,
            cam = CamStreamState(stream2Active, stats2, stats2?.message ?: stream2EndMessage, config.camUrl, config.camAudio,
                config.camResolution, config.camBitrateKbps), webCaptureFps = config.webCaptureFps, webGpu = config.webCaptureGpu, allowCleartext = config.allowCleartextWeb,
            cameraRotationFix = config.cameraRotationFix,
            useFullUrl = config.useFullUrl, fullUrl = config.fullUrl, serverUrl = config.serverUrl, streamKey = config.streamKey,
            availableResolutions = availableResolutions(),
            cameraSizes = if (ready) camera.outputSizes.take(6).joinToString { "${it.width}×${it.height}" } else "",
            notice = notice,
            media = HashMap(mediaStatus),
            audioLevels = audioLevels(),
            scenes = sceneStore.scenes.map { SceneInfo(it.id, it.name, it.savedAt, it.orientation, it.resolution, it.fps, it.layerTypes) },
            activeSceneId = sceneStore.activeId?.takeIf { id -> sceneStore.find(id) != null },
        )
    }

    private var lastFailed = false

    private fun audioLevels(): Map<String, Float> {
        val m = HashMap<String, Float>()
        for (l in config.layers) when (l.type) {
            LayerType.CAMERA -> m[l.id] = if (config.micMuted) 0f else micLevel
            LayerType.MEDIA, LayerType.WEB -> if (ready) m[l.id] = audio.mixer.level(l.id)
            else -> {}
        }
        return m
    }

    private fun changed() { onChanged?.invoke() }

    private fun availableResolutions(): List<Resolution> {
        cachedResolutions?.let { return it }
        val list = Resolution.values().filter {
            VideoEncoder.supports(it.long, it.short, 30) || VideoEncoder.supports(it.short, it.long, 30)
        }.ifEmpty { listOf(Resolution.P720) }
        cachedResolutions = list
        return list
    }

    fun consumeNotice() { notice = null; changed() }
    fun notify(msg: String) { notice = msg; changed() }

    // ------------------------------------------------------------------ 生命周期

    /** 权限已授予后调用一次。 */
    fun init(previewSurface: Surface?, pw: Int, ph: Int) {
        if (ready) return
        ready = true
        compositor = Compositor(config.canvasWidth, config.canvasHeight, config.fps)
        camera = CameraSource(activity, object : CameraSource.Listener {
            override fun onCameraState(state: CameraSource.State, message: String?) {
                main.post { cameraMessage = message; changed() }
            }
        })
        audio = AudioPipeline(
            onLevel = { lv -> main.post { micLevel = lv; changed() } },
            onError = { msg -> main.post { micMessage = msg; changed() } },
        )
        audio.muted = config.micMuted
        audio.micVolume = cameraLayer()?.volume ?: 1f
        audio.noiseReducer.enabled = config.noiseSuppression
        stream = StreamController(compositor, audio, object : StreamController.Listener {
            override fun onStats(stats: StreamController.Stats) {
                this@StudioEngine.stats = stats
                if (stats.durationSec % 5 == 0L) logRow(stats)
                changed()
            }

            override fun onStreamEnded(error: String?) {
                lastFailed = error != null
                streamActive = false
                stats = null
                streamEndMessage = error
                runLogger.finish()?.let { notify("运行日志已保存到 $it") }
                changed()
            }
        })
        stream2 = StreamController(compositor, audio, object : StreamController.Listener {
            override fun onStats(stats: StreamController.Stats) { stats2 = stats; changed() }
            override fun onStreamEnded(error: String?) {
                stream2Active = false; stats2 = null; stream2EndMessage = error
                changed()
            }
        }, StreamController.Kind.CAMERA)
        compositor.start { st -> main.post { cameraTexture = st; if (started) openCamera() } }
        if (previewSurface != null) compositor.setPreviewSurface(previewSurface, pw, ph)
        setupWebLayer()
        config.layers.filter { it.type == LayerType.MEDIA }.forEach { setupMedia(it) }
        pushScene()
        registerNetworkCallback()
        main.post(perfTick)
        changed()
    }

    fun onStart() {
        started = true
        if (!ready) return
        openCamera()
        micMessage = null
        audio.startCapture()
        players.values.forEach { it.resume() }
        changed()
    }

    /** 首版：切后台 / 锁屏即停止推流并释放采集设备。返回是否因此停止了直播。 */
    fun onStop(): Boolean {
        started = false
        if (!ready) return false
        var stopped = false
        if (stream.isActive) { stream.stop("应用进入后台，推流已自动停止"); stopped = true }
        if (stream2.isActive) { stream2.stop("应用进入后台，第二路已自动停止"); stopped = true }
        camera.close()
        audio.stopCapture()
        players.values.forEach { it.pause() }
        config.save(activity)
        return stopped
    }

    fun release() {
        if (!ready) return
        main.removeCallbacks(perfTick)
        networkCallback?.let {
            try { activity.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        stream.stop()
        stream2.stop()
        web?.release()
        players.values.forEach { it.release() }; players.clear()
        camera.release()
        audio.stopCapture()
        compositor.release()
        ready = false
    }

    fun setPreviewSurface(surface: Surface?, w: Int, h: Int) {
        if (ready) compositor.setPreviewSurface(surface, w, h)
    }

    /** 屏幕方向变化后（Activity 不重建）重新计算摄像头旋转。 */
    fun onDisplayRotationChanged() { if (ready && started) scheduleOpenCamera() }

    /** 合并短时间内的多次重开（切换画布方向时：设置变更 + 屏幕旋转会各触发一次）。 */
    private val openCameraRunnable = Runnable { if (ready && started) openCamera() }
    private fun scheduleOpenCamera() {
        main.removeCallbacks(openCameraRunnable)
        main.postDelayed(openCameraRunnable, 250)
    }

    // ------------------------------------------------------------------ 摄像头

    private fun openCamera() {
        val st = cameraTexture ?: return
        val size = camera.chooseSize(config.resolution.long, config.resolution.short)
        compositor.setCameraGeometry(size.width, size.height, camera.sensorOrientation, cameraRotation())
        camera.open(st, size)
        camera.setZoom(config.zoom)
    }

    private fun cameraRotation(): Int {
        @Suppress("DEPRECATION")
        val r = when (activity.windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (r + config.cameraRotationFix) % 360
    }

    fun focusAt(canvasX: Float, canvasY: Float) {
        if (!ready) return
        compositor.canvasToCameraUv(canvasX, canvasY)?.let { (u, v) -> camera.focusAt(u, v) }
    }

    fun setZoom(z: Float) {
        if (!ready) return
        config.zoom = z.coerceIn(camera.zoomRange.lower, camera.zoomRange.upper)
        camera.setZoom(config.zoom)
        changed()
    }

    fun retryCapture() {
        if (!ready) return
        cameraMessage = null; micMessage = null
        camera.retry()
        audio.stopCapture(); audio.startCapture()
        if (webState == WebOverlaySource.LoadState.CRASHED || webState == WebOverlaySource.LoadState.ERROR) reloadWeb()
        changed()
    }

    // ------------------------------------------------------------------ 图层

    fun webLayer(): Layer? = config.layers.firstOrNull { it.type == LayerType.WEB }

    /** 隐藏的网页 / 媒体图层不出声（与 OBS 隐藏来源的行为一致）；摄像头图层的声音是麦克风，不受隐藏影响。 */
    private fun effectiveVolume(l: Layer): Float = if (l.visible || l.type == LayerType.CAMERA) l.volume else 0f

    fun cameraLayer(): Layer? = config.layers.firstOrNull { it.type == LayerType.CAMERA }

    // ------------------------------------------------------------------ 媒体图层（拉流 / 本地视频）

    private fun setupMedia(l: Layer) {
        players.remove(l.id)?.release()
        compositor.releaseExternalSurface(l.id)
        mediaStatus.remove(l.id)
        if (l.mediaUri.isBlank()) { pushScene(); return }
        val src = PlayerSource(activity, l.id, l.mediaUri, l.mediaLive, l.loop, audio.mixer, effectiveVolume(l),
            object : PlayerSource.Listener {
                override fun onStateChanged(state: PlayerSource.State, message: String?) {
                    if (players[l.id] == null) return
                    val old = mediaStatus[l.id]
                    mediaStatus[l.id] = MediaStatus(state, message, old?.videoW ?: 0, old?.videoH ?: 0)
                    pushScene(); changed()
                }

                override fun onVideoSize(width: Int, height: Int) {
                    val cur = config.layers.firstOrNull { it.id == l.id } ?: return
                    val old = mediaStatus[l.id]
                    mediaStatus[l.id] = MediaStatus(old?.state ?: PlayerSource.State.PLAYING, old?.message, width, height)
                    if (autoSize.remove(l.id)) {
                        // 保持中心，图层框改成画面比例并放进原来的框里
                        val a = width.toFloat() / height
                        val cx = cur.x + cur.w / 2; val cy = cur.y + cur.h / 2
                        var w = cur.w; var h = w / a
                        if (h > cur.h) { h = cur.h; w = h * a }
                        cur.w = w; cur.h = h; cur.x = cx - w / 2; cur.y = cy - h / 2
                        config.save(activity)
                    }
                    pushScene(); changed()
                }
            })
        players[l.id] = src
        src.setMonitor(monitorVolume(l))
        mediaStatus[l.id] = MediaStatus(PlayerSource.State.CONNECTING, null, 0, 0)
        compositor.createExternalSurface(l.id, 1280, 720) { surface ->
            main.post { if (players[l.id] === src) { src.attach(surface); if (!started) src.pause() } }
        }
    }

    /** 是否网络视频（点播 http(s) 地址，非拉流）。 */
    private fun isRemoteVideo(uri: String) = uri.startsWith("http://", true) || uri.startsWith("https://", true)

    /**
     * 添加媒体图层。live=true 为拉流地址；false 为视频文件：本地（content:// URI）或网络视频（http(s) 地址）。
     * loop 只对视频文件生效。
     */
    fun addMediaLayer(uri: String, live: Boolean, loop: Boolean = true): Layer? {
        val u = uri.trim()
        val remote = !live && isRemoteVideo(u)
        if (u.isEmpty()) { notify(if (live) "请输入拉流地址" else "请输入视频地址"); return null }
        if (live && !SUPPORTED_PULL.any { u.startsWith(it, ignoreCase = true) }) {
            notify("拉流地址需以 http(s)://、rtmp://、rtsp:// 开头"); return null
        }
        if (!live && !remote && !u.startsWith("content://") && !u.startsWith("file://")) {
            notify("视频地址需以 http:// 或 https:// 开头"); return null
        }
        if (config.layers.count { it.type == LayerType.MEDIA } >= MAX_MEDIA) { notify("最多同时添加 $MAX_MEDIA 路媒体（受手机解码器数量限制）"); return null }
        val n = config.layers.count { it.type == LayerType.MEDIA } + 1
        val cw = config.canvasWidth.toFloat(); val ch = config.canvasHeight.toFloat()
        var w = cw / 2; var h = w * 9 / 16
        if (h > ch / 2) { h = ch / 2; w = h * 16 / 9 }
        val name = when { live -> "拉流 $n"; remote -> "网络视频 $n"; else -> "视频 $n" }
        val l = Layer(type = LayerType.MEDIA, name = name,
            x = (cw - w) / 2, y = (ch - h) / 2, w = w, h = h, mediaUri = u, mediaLive = live, loop = loop)
        config.layers.add(l)
        autoSize.add(l.id)
        config.save(activity)
        if (ready) setupMedia(l)
        pushScene(); changed()
        return l
    }

    /** 修改媒体图层的地址 / 循环设置，并重新开始播放。 */
    fun updateMedia(id: String, uri: String, loop: Boolean) {
        val l = config.layers.firstOrNull { it.id == id && it.type == LayerType.MEDIA } ?: return
        val u = uri.trim()
        if (u == l.mediaUri && loop == l.loop) return
        if (!l.mediaLive && u != l.mediaUri && !isRemoteVideo(u)) { notify("视频地址需以 http:// 或 https:// 开头"); return }
        val onlyLoop = u == l.mediaUri
        l.mediaUri = u; l.loop = loop
        config.save(activity)
        // 只改循环：不重新加载，直接切换播放器的循环模式，画面不中断
        val p = players[id]
        if (onlyLoop && p != null) p.setLoop(loop)
        else if (ready) setupMedia(l)
        changed()
    }

    fun reloadMedia(id: String) {
        val l = config.layers.firstOrNull { it.id == id && it.type == LayerType.MEDIA } ?: return
        if (ready) setupMedia(l)
        changed()
    }

    private fun setupWebLayer() {
        web?.release(); web = null
        webOutputVisible = false
        webAudio = null
        val l = webLayer() ?: run { webState = WebOverlaySource.LoadState.IDLE; return }
        compositor.releaseExternalSurface(l.id)
        val src = WebOverlaySource(activity, webParent, l.viewportW, l.viewportH, config.webCaptureGpu,
            config.allowCleartextWeb, webFpsTarget(), object : WebOverlaySource.Listener {
                override fun onLoadStateChanged(state: WebOverlaySource.LoadState, message: String?) {
                    webState = state; webMessage = message; changed()
                }
                override fun onOutputVisibilityChanged(visible: Boolean) { webOutputVisible = visible; pushScene() }
                override fun onViewportMeasured(cssWidth: Int, cssHeight: Int, dpr: Float) {
                    webViewport = "${cssWidth}×$cssHeight"; changed()
                }
                override fun onAudioStatus(summary: String?) {
                    webAudio = summary; changed()
                }
            }, audio.mixer, l.id, effectiveVolume(l))
        web = src
        src.monitorGain = monitorVolume(l)
        compositor.createExternalSurface(l.id, l.viewportW, l.viewportH) { surface ->
            main.post {
                if (web !== src) return@post
                src.attach(surface)
                if (l.url.isNotBlank()) src.load(l.url)
            }
        }
    }

    fun reloadWeb() { if (web == null) setupWebLayer() else web?.reload() }

    /** 修改网页 URL / 渲染视口。视口变化需要重建网页。 */
    fun updateWeb(url: String, viewportW: Int, viewportH: Int) {
        val l = webLayer() ?: return
        val vpChanged = viewportW != l.viewportW || viewportH != l.viewportH
        l.url = url.trim(); l.viewportW = viewportW; l.viewportH = viewportH
        config.save(activity)
        if (!ready) return
        if (vpChanged || web == null) setupWebLayer() else web?.load(l.url)
        changed()
    }

    /** 图层变换/属性修改（直播中可用）。persist=false 用于拖动过程中的高频更新。 */
    fun updateLayer(updated: Layer, persist: Boolean = true) {
        val l = config.layers.firstOrNull { it.id == updated.id } ?: return
        l.name = updated.name; l.x = updated.x; l.y = updated.y; l.w = updated.w; l.h = updated.h
        l.visible = updated.visible; l.locked = updated.locked; l.opacity = updated.opacity
        l.text = updated.text; l.textSizePx = updated.textSizePx; l.textColor = updated.textColor
        l.volume = updated.volume.coerceIn(0f, 2f)
        if (ready) when (l.type) {
            LayerType.CAMERA -> audio.micVolume = l.volume
            LayerType.MEDIA, LayerType.WEB -> { audio.mixer.setVolume(l.id, effectiveVolume(l)); applyMonitor() }
            else -> {}
        }
        pushScene()
        if (persist) config.save(activity)
        changed()
    }

    fun moveLayer(id: String, toIndex: Int) {
        val i = config.layers.indexOfFirst { it.id == id }
        if (i < 0) return
        val l = config.layers.removeAt(i)
        config.layers.add(toIndex.coerceIn(0, config.layers.size), l)
        pushScene(); config.save(activity); changed()
    }

    fun deleteLayer(id: String) {
        val l = config.layers.firstOrNull { it.id == id } ?: return
        if (l.type == LayerType.CAMERA) { notify("摄像头图层不能删除，可以隐藏或缩小"); return }
        config.layers.remove(l)
        if (l.type == LayerType.MEDIA) {
            players.remove(l.id)?.release(); mediaStatus.remove(l.id); autoSize.remove(l.id)
            if (ready) compositor.releaseExternalSurface(l.id)
        } else if (l.type == LayerType.WEB) {
            web?.release(); web = null; webOutputVisible = false
            webState = WebOverlaySource.LoadState.IDLE; webMessage = null; webViewport = null
            if (ready) compositor.releaseExternalSurface(l.id)
        } else if (ready) {
            compositor.removeBitmap(l.id); bitmapKeys.remove(l.id)
        }
        pushScene(); config.save(activity); changed()
    }

    fun addWebLayer(url: String): Layer? {
        if (webLayer() != null) { notify("首版只支持一个网页图层"); return null }
        val l = Layer(type = LayerType.WEB, name = "网页", x = 0f, y = 0f,
            w = config.canvasWidth.toFloat(), h = config.canvasHeight.toFloat(),
            url = url, viewportW = config.canvasWidth, viewportH = config.canvasHeight)
        config.layers.add(l)
        config.save(activity)
        if (ready) setupWebLayer()
        pushScene(); changed()
        return l
    }

    fun addImageLayer(uri: Uri): Layer? {
        val bmp = loadImage(uri) ?: run { notify("无法读取图片"); return null }
        val maxW = config.canvasWidth / 3f
        val scale = minOf(1f, maxW / bmp.width)
        val n = config.layers.count { it.type == LayerType.IMAGE } + 1
        val l = Layer(type = LayerType.IMAGE, name = "图片 $n",
            x = (config.canvasWidth - bmp.width * scale) / 2, y = (config.canvasHeight - bmp.height * scale) / 2,
            w = bmp.width * scale, h = bmp.height * scale, imageUri = uri.toString())
        config.layers.add(l)
        pushScene(); config.save(activity); changed()
        return l
    }

    fun addTextLayer(text: String): Layer {
        val n = config.layers.count { it.type == LayerType.TEXT } + 1
        val l = Layer(type = LayerType.TEXT, name = "文字 $n", x = 0f, y = 0f, w = 0f, h = 0f,
            text = text, textSizePx = config.canvasHeight / 14f)
        val bmp = renderText(l)
        l.w = bmp.width.toFloat(); l.h = bmp.height.toFloat()
        l.x = (config.canvasWidth - l.w) / 2; l.y = config.canvasHeight * 0.75f - l.h / 2
        config.layers.add(l)
        pushScene(); config.save(activity); changed()
        return l
    }

    /** 文字图层改字号后，按新内容尺寸调整显示框（保持中心）。 */
    fun fitTextLayer(id: String) {
        val l = config.layers.firstOrNull { it.id == id && it.type == LayerType.TEXT } ?: return
        val bmp = renderText(l)
        val cx = l.x + l.w / 2; val cy = l.y + l.h / 2
        l.w = bmp.width.toFloat(); l.h = bmp.height.toFloat()
        l.x = cx - l.w / 2; l.y = cy - l.h / 2
        pushScene(); config.save(activity); changed()
    }

    private fun pushScene() {
        if (!ready) return
        val list = ArrayList<Compositor.RenderLayer>()
        for (l in config.layers) {
            if (!l.visible) continue
            when (l.type) {
                LayerType.CAMERA -> list.add(Compositor.RenderLayer(l.id, Compositor.RenderLayer.Kind.CAMERA, l.x, l.y, l.w, l.h, l.opacity))
                LayerType.MEDIA -> {
                    val st = mediaStatus[l.id] ?: continue
                    if (st.videoW <= 0 || st.state == PlayerSource.State.ERROR) continue
                    // 画面等比放进图层框（留边透明）
                    val a = st.videoW.toFloat() / st.videoH
                    var w = l.w; var h = w / a
                    if (h > l.h) { h = l.h; w = h * a }
                    list.add(Compositor.RenderLayer(l.id, Compositor.RenderLayer.Kind.EXTERNAL,
                        l.x + (l.w - w) / 2, l.y + (l.h - h) / 2, w, h, l.opacity))
                }
                LayerType.WEB -> if (webOutputVisible) list.add(Compositor.RenderLayer(l.id, Compositor.RenderLayer.Kind.EXTERNAL, l.x, l.y, l.w, l.h, l.opacity))
                LayerType.IMAGE, LayerType.TEXT -> if (ensureBitmap(l)) list.add(Compositor.RenderLayer(l.id, Compositor.RenderLayer.Kind.BITMAP, l.x, l.y, l.w, l.h, l.opacity))
            }
        }
        compositor.setScene(list)
    }

    private fun ensureBitmap(l: Layer): Boolean {
        val key = when (l.type) {
            LayerType.IMAGE -> "img:${l.imageUri}"
            LayerType.TEXT -> "txt:${l.text}:${l.textSizePx}:${l.textColor}"
            else -> return false
        }
        if (bitmapKeys[l.id] == key) return true
        val bmp = when (l.type) {
            LayerType.IMAGE -> loadImage(Uri.parse(l.imageUri)) ?: return false
            else -> renderText(l)
        }
        bitmapKeys[l.id] = key
        compositor.setBitmap(l.id, bmp)
        return true
    }

    private fun loadImage(uri: Uri): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 2048) sample *= 2
        activity.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (_: Exception) {
        null
    }

    private fun renderText(l: Layer): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = l.textSizePx; color = l.textColor
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(l.textSizePx / 12f, 0f, l.textSizePx / 24f, 0xB0000000.toInt())
        }
        val lines = l.text.ifEmpty { " " }.split('\n')
        val fm = p.fontMetrics
        val lineH = fm.descent - fm.ascent
        val pad = l.textSizePx / 6f
        val w = (lines.maxOf { p.measureText(it) } + pad * 2).toInt().coerceAtLeast(8)
        val h = (lineH * lines.size + pad * 2).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        lines.forEachIndexed { i, s -> c.drawText(s, pad, pad - fm.ascent + i * lineH, p) }
        return bmp
    }

    /** 网页交互模式：画布坐标的触摸转发给网页。 */
    fun dispatchWebTouch(ev: MotionEvent, canvasX: Float, canvasY: Float): Boolean {
        val l = webLayer() ?: return false
        val src = web ?: return false
        src.dispatchTouch(ev, (canvasX - l.x) / l.w * l.viewportW, (canvasY - l.y) / l.h * l.viewportH)
        return true
    }

    // ------------------------------------------------------------------ 推流

    /** 校验推流地址，返回错误信息（null 表示可用）。 */
    fun validateUrl(): String? = try {
        if (config.useFullUrl) RtmpUrl.parse(config.fullUrl) else RtmpUrl.fromServerAndKey(config.serverUrl, config.streamKey)
        null
    } catch (e: RtmpUrlException) {
        e.message
    }

    fun startStream(): String? {
        if (!ready) return "正在初始化"
        if (stream.isActive) return null
        val url = try {
            if (config.useFullUrl) RtmpUrl.parse(config.fullUrl) else RtmpUrl.fromServerAndKey(config.serverUrl, config.streamKey)
        } catch (e: RtmpUrlException) {
            return e.message
        }
        config.save(activity)
        streamEndMessage = null; lastFailed = false; stats = null
        runLogger.begin("${config.canvasWidth}x${config.canvasHeight}@${config.fps} ${config.videoBitrateKbps}kbps " +
            "web=${if (config.webCaptureGpu) "GPU" else "CPU"} ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} ${url.masked()}")
        streamActive = true
        stream.start(url, config.canvasWidth, config.canvasHeight, config.fps, config.videoBitrateKbps * 1000, config.keyFrameIntervalSec)
        if (!stream.isActive) streamActive = false
        changed()
        return null
    }

    fun stopStream() { if (ready) stream.stop() }

    // ------------------------------------------------------------------ 第二路：纯摄像头画面

    /** 第二路输出尺寸：方向跟随画布，默认 720p。 */
    private fun camStreamSize(): Pair<Int, Int> {
        val r = config.camResolution
        return if (config.orientation == CanvasOrientation.LANDSCAPE) r.long to r.short else r.short to r.long
    }

    fun validateCamUrl(): String? = try { RtmpUrl.parse(config.camUrl); null } catch (e: RtmpUrlException) { e.message }

    /** 开始第二路推流（与主推流独立，可以单独开、单独停）。返回错误信息或 null。 */
    fun startCamStream(): String? {
        if (!ready) return "正在初始化"
        if (stream2.isActive) return null
        val url = try { RtmpUrl.parse(config.camUrl) } catch (e: RtmpUrlException) { return e.message }
        if (config.useFullUrl && RtmpUrl.sanitize(config.fullUrl) == RtmpUrl.sanitize(config.camUrl)) return "第二路地址和主推流地址相同，请换一个"
        config.save(activity)
        stream2EndMessage = null; stats2 = null
        val (w, h) = camStreamSize()
        stream2Active = true
        stream2.start(url, w, h, minOf(config.fps, 30), config.camBitrateKbps * 1000, config.keyFrameIntervalSec, withAudio = config.camAudio)
        if (!stream2.isActive) stream2Active = false
        changed()
        return null
    }

    fun stopCamStream() { if (ready) stream2.stop() }

    fun updateCamStream(edit: (AppConfig) -> Unit) {
        edit(config)
        config.save(activity)
        changed()
    }

    fun toggleMute() {
        config.micMuted = !config.micMuted
        if (ready) audio.muted = config.micMuted
        config.save(activity); changed()
    }

    // ------------------------------------------------------------------ 设置（非直播时）

    fun applySettings(edit: (AppConfig) -> Unit) {
        val oldW = config.canvasWidth; val oldH = config.canvasHeight
        val oldFps = config.fps; val oldGpu = config.webCaptureGpu; val oldHttp = config.allowCleartextWeb
        val oldRot = config.cameraRotationFix; val oldRes = config.resolution
        val oldOrientation = config.orientation
        edit(config)
        val newW = config.canvasWidth; val newH = config.canvasHeight
        if (oldW != newW || oldH != newH) relayout(oldOrientation, oldW, oldH)
        config.save(activity)
        if (!ready) { changed(); return }
        audio.noiseReducer.enabled = config.noiseSuppression
        if (oldOrientation != config.orientation && stream2.isActive) stream2.stop("画布方向变了，第二路已停止，请重新开始")
        web?.setCaptureFps(webFpsTarget())
        applyMonitor()
        updatePowerSave()
        if (oldW != newW || oldH != newH || oldFps != config.fps) compositor.configure(newW, newH, config.fps)
        if (oldW != newW || oldH != newH || oldGpu != config.webCaptureGpu || oldHttp != config.allowCleartextWeb || oldFps != config.fps) setupWebLayer()
        if (oldRes != config.resolution || oldRot != config.cameraRotationFix || oldW != newW) scheduleOpenCamera()
        pushScene()
        changed()
    }

    /**
     * 画布尺寸变化后重新摆放图层。
     * 横屏和竖屏各记一份布局（按画布比例归一化保存在图层里）：切回去时原样恢复，
     * 不会因为每次换算都缩小一点而越切越小。第一次切到某个方向时：铺满画布的图层继续铺满，
     * 其他图层保持中心的相对位置，等比缩放到能放进新画布。
     */
    private fun relayout(oldOrientation: CanvasOrientation, oldW: Int, oldH: Int) {
        val newW = config.canvasWidth.toFloat(); val newH = config.canvasHeight.toFloat()
        val newKey = config.orientation.name
        for (l in config.layers) {
            // 记下旧方向的布局
            l.layouts[oldOrientation.name] = floatArrayOf(l.x / oldW, l.y / oldH, l.w / oldW, l.h / oldH)
            val saved = l.layouts[newKey]
            val full = abs(l.x) < 1f && abs(l.y) < 1f && abs(l.w - oldW) < 1f && abs(l.h - oldH) < 1f
            when {
                saved != null && oldOrientation != config.orientation -> {
                    l.x = saved[0] * newW; l.y = saved[1] * newH; l.w = saved[2] * newW; l.h = saved[3] * newH
                }
                full -> { l.x = 0f; l.y = 0f; l.w = newW; l.h = newH }
                oldOrientation == config.orientation -> {
                    // 只换分辨率：整体等比缩放（可逆）
                    val k = newW / oldW
                    l.x *= k; l.y *= k; l.w *= k; l.h *= k
                }
                else -> {
                    val s = minOf(1f, newW / l.w, newH / l.h)
                    val cx = (l.x + l.w / 2) / oldW * newW; val cy = (l.y + l.h / 2) / oldH * newH
                    l.w *= s; l.h *= s
                    l.x = (cx - l.w / 2).coerceIn(0f, newW - l.w); l.y = (cy - l.h / 2).coerceIn(0f, newH - l.h)
                }
            }
            if (l.type == LayerType.WEB && abs(l.w - newW) < 1f && abs(l.h - newH) < 1f) {
                l.viewportW = newW.toInt(); l.viewportH = newH.toInt()
            }
        }
    }

    // ------------------------------------------------------------------ 场景方案

    /** 把当前画面保存为新场景，返回场景 id。 */
    fun saveScene(name: String): String {
        val n = name.trim().ifEmpty { "场景 ${sceneStore.scenes.size + 1}" }
        val sc = Scene(name = n, savedAt = System.currentTimeMillis(), data = config.toSceneData())
        sceneStore.scenes.add(sc)
        sceneStore.activeId = sc.id
        notify("已保存场景“$n”")
        changed()
        return sc.id
    }

    /** 用当前画面覆盖已有场景。 */
    fun overwriteScene(id: String) {
        val i = sceneStore.scenes.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = sceneStore.scenes[i]
        sceneStore.scenes[i] = old.copy(savedAt = System.currentTimeMillis(), data = config.toSceneData())
        sceneStore.activeId = id
        notify("已更新场景“${old.name}”")
        changed()
    }

    fun renameScene(id: String, name: String) {
        val i = sceneStore.scenes.indexOfFirst { it.id == id }
        if (i < 0 || name.isBlank()) return
        sceneStore.scenes[i] = sceneStore.scenes[i].copy(name = name.trim())
        sceneStore.persist(); changed()
    }

    fun deleteScene(id: String) {
        sceneStore.scenes.removeAll { it.id == id }
        if (sceneStore.activeId == id) sceneStore.activeId = null else sceneStore.persist()
        changed()
    }

    fun moveScene(id: String, delta: Int) {
        val i = sceneStore.scenes.indexOfFirst { it.id == id }
        if (i < 0) return
        val j = (i + delta).coerceIn(0, sceneStore.scenes.size - 1)
        if (i == j) return
        val sc = sceneStore.scenes.removeAt(i); sceneStore.scenes.add(j, sc)
        sceneStore.persist(); changed()
    }

    /**
     * 切换到场景：替换全部图层并重建网页、媒体、图片/文字来源。
     * 未直播时同时应用画布、编码等设置；直播中画布和编码不能变，只切换图层，并按当前画布换算图层位置。
     */
    fun applyScene(id: String) {
        val sc = sceneStore.find(id) ?: return
        val d = sc.data
        val live = streamActive
        val oldW = config.canvasWidth; val oldH = config.canvasHeight; val oldFps = config.fps
        val oldRes = config.resolution; val oldRot = config.cameraRotationFix
        val sceneOrientation = sc.orientation
        val sceneRes = sc.resolution
        val sceneW = if (sceneOrientation == CanvasOrientation.LANDSCAPE) sceneRes.long else sceneRes.short
        val sceneH = if (sceneOrientation == CanvasOrientation.LANDSCAPE) sceneRes.short else sceneRes.long

        teardownSources()
        config.layers.clear()
        config.layers.addAll(sceneLayers(d))
        if (config.layers.none { it.type == LayerType.CAMERA }) {
            config.layers.add(0, Layer(type = LayerType.CAMERA, name = "后置摄像头", x = 0f, y = 0f, w = sceneW.toFloat(), h = sceneH.toFloat()))
        }

        var skipped = false
        if (!live) {
            if (sceneOrientation != config.orientation && stream2.isActive) stream2.stop("画布方向变了，第二路已停止，请重新开始")
            config.orientation = sceneOrientation
            config.resolution = if (sceneRes in availableResolutions()) sceneRes else config.resolution
            config.fps = d.optInt("fps", config.fps)
            config.videoBitrateKbps = d.optInt("videoBitrateKbps", config.videoBitrateKbps)
            config.keyFrameIntervalSec = d.optInt("keyFrameIntervalSec", config.keyFrameIntervalSec)
            config.webCaptureGpu = d.optBoolean("webCaptureGpu", config.webCaptureGpu)
            config.allowCleartextWeb = d.optBoolean("allowCleartextWeb", config.allowCleartextWeb)
            config.cameraRotationFix = d.optInt("cameraRotationFix", config.cameraRotationFix)
        } else {
            skipped = sceneW != oldW || sceneH != oldH || sc.fps != oldFps
        }
        config.zoom = d.optDouble("zoom", config.zoom.toDouble()).toFloat()
        // 场景保存时的画布与现在不同（直播中，或本机不支持该分辨率）：按当前画布换算图层
        if (sceneW != config.canvasWidth || sceneH != config.canvasHeight) relayout(sceneOrientation, sceneW, sceneH)

        sceneStore.activeId = id
        config.save(activity)
        if (ready) {
            if (oldW != config.canvasWidth || oldH != config.canvasHeight || oldFps != config.fps) {
                compositor.configure(config.canvasWidth, config.canvasHeight, config.fps)
            }
            audio.micVolume = cameraLayer()?.volume ?: 1f
            setupWebLayer()
            config.layers.filter { it.type == LayerType.MEDIA }.forEach { setupMedia(it) }
            if (oldRes != config.resolution || oldRot != config.cameraRotationFix || oldW != config.canvasWidth) scheduleOpenCamera()
            else camera.setZoom(config.zoom)
            pushScene()
        }
        notify(if (skipped) "已切换到“${sc.name}”。直播中画布和编码不变，场景里的这些设置停播后重新切换才会生效"
               else "已切换到“${sc.name}”")
        changed()
    }

    /** 释放所有图层来源（切换场景前）。摄像头不受影响。 */
    private fun teardownSources() {
        web?.release(); web = null; webOutputVisible = false
        webState = WebOverlaySource.LoadState.IDLE; webMessage = null; webViewport = null
        players.values.forEach { it.release() }; players.clear()
        mediaStatus.clear(); autoSize.clear()
        if (ready) {
            for (l in config.layers) when (l.type) {
                LayerType.WEB, LayerType.MEDIA -> compositor.releaseExternalSurface(l.id)
                LayerType.IMAGE, LayerType.TEXT -> compositor.removeBitmap(l.id)
                else -> {}
            }
            compositor.setScene(emptyList())
        }
        bitmapKeys.clear()
    }

    fun setSyncTest(on: Boolean) {
        syncTest = on
        if (ready) { compositor.syncTest = on; audio.syncTest = on }
        changed()
    }

    // ------------------------------------------------------------------ 网络 & 性能

    private fun registerNetworkCallback() {
        val cm = activity.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { if (ready) { stream.onNetworkAvailable(); stream2.onNetworkAvailable() } } }
            override fun onLost(network: Network) { main.post { if (ready && cm.activeNetwork == null) { stream.onNetworkLost(); stream2.onNetworkLost() } } }
        }
        try { cm.registerDefaultNetworkCallback(cb); networkCallback = cb } catch (_: Exception) {}
    }

    private var lastRendered = 0L
    private var lastWebFrames = 0L
    private var perfTicks = 0
    private val perfTick = object : Runnable {
        override fun run() {
            if (!ready) return
            val r = compositor.renderedFrames
            val w = web?.captureFrames ?: 0L
            renderFps = (r - lastRendered).coerceAtLeast(0).toInt()
            webFps = (w - lastWebFrames).coerceAtLeast(0).toInt()
            lastRendered = r; lastWebFrames = w
            if (perfTicks++ % 5 == 0) metrics = try { metricsSampler.sample() } catch (_: Exception) { null }
            updatePowerSave()
            checkThermal()
            changed()
            main.postDelayed(this, 1000)
        }
    }

    /** 本机监听音量：开启“在手机上也播放”时跟随图层音量（最高 100%），否则 0。 */
    private fun monitorVolume(l: Layer): Float = if (config.monitorLocal) effectiveVolume(l).coerceAtMost(1f) else 0f

    private fun applyMonitor() {
        for (l in config.layers) when (l.type) {
            LayerType.MEDIA -> players[l.id]?.setMonitor(monitorVolume(l))
            LayerType.WEB -> web?.monitorGain = monitorVolume(l)
            else -> {}
        }
    }

    /** 网页实际采集帧率：设置值（0 = 跟随输出），不超过输出帧率。 */
    private fun webFpsTarget(): Int = if (config.webCaptureFps > 0) minOf(config.webCaptureFps, config.fps) else config.fps

    /** 直播省电：预览降到约 15fps（编码输出仍是完整帧率）。 */
    private fun updatePowerSave() {
        if (!ready) return
        compositor.previewEvery = if (config.powerSave && (streamActive || stream2Active)) maxOf(1, config.fps / 15) else 1
    }

    private val power = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var thermalWarned = PowerManager.THERMAL_STATUS_NONE

    /** 直播中手机过热时提醒一次（每升一级提醒一次）。 */
    private fun checkThermal() {
        val st = power.currentThermalStatus
        if (st < PowerManager.THERMAL_STATUS_SEVERE) {
            if (st < PowerManager.THERMAL_STATUS_MODERATE) thermalWarned = PowerManager.THERMAL_STATUS_NONE
            return
        }
        if (st <= thermalWarned || !(streamActive || stream2Active)) return
        thermalWarned = st
        val tips = buildList {
            if (config.resolution != Resolution.P720) add("降到 720p")
            if (config.fps > 30) add("帧率降到 30")
            if (web != null && webFpsTarget() > 15) add("网页刷新率调到 15fps 以下")
            if (players.isNotEmpty()) add("减少视频图层")
            add("给手机散热、取下手机壳")
        }
        notify("手机温度过高，系统可能会降频导致掉帧。建议：${tips.joinToString("、")}")
    }

    private fun logRow(st: StreamController.Stats) {
        val m = metrics
        runLogger.append(listOf(st.durationSec, st.state.name, "%.1f".format(st.outputFps), "%.0f".format(st.bitrateKbps),
            st.droppedFrames, st.renderLate, st.reconnects, "%.1f".format(web?.lastCaptureMs ?: 0.0), webFps,
            m?.pssMb ?: 0, m?.javaMb ?: 0, m?.batteryTempC ?: 0f, m?.thermal ?: ""))
    }

    companion object {
        /** 同时解码的视频路数上限（加上摄像头和编码器，多数手机硬件编解码实例在 4~8 个之间）。 */
        const val MAX_MEDIA = 3
        private val SUPPORTED_PULL = listOf("http://", "https://", "rtmp://", "rtsp://")
    }
}
