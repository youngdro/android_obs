package app.livecomposer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.livecomposer.core.AppConfig
import app.livecomposer.core.CanvasOrientation
import app.livecomposer.core.Layer
import app.livecomposer.core.LayerType
import app.livecomposer.core.Resolution
import app.livecomposer.encode.AudioPipeline
import app.livecomposer.gl.Compositor
import app.livecomposer.rtmp.RtmpPublisher
import app.livecomposer.rtmp.RtmpUrl
import app.livecomposer.rtmp.RtmpUrlException
import app.livecomposer.source.CameraSource
import app.livecomposer.source.WebOverlaySource
import app.livecomposer.stream.DeviceMetrics
import app.livecomposer.stream.RunLogger
import app.livecomposer.stream.StreamController
import app.livecomposer.encode.VideoEncoder
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import app.livecomposer.ui.EditOverlayView
import app.livecomposer.ui.button
import app.livecomposer.ui.check
import app.livecomposer.ui.column
import app.livecomposer.ui.dp
import app.livecomposer.ui.edit
import app.livecomposer.ui.label
import app.livecomposer.ui.panelBackground
import app.livecomposer.ui.row
import app.livecomposer.ui.spinner

class MainActivity : Activity() {

    private lateinit var config: AppConfig
    private lateinit var compositor: Compositor
    private lateinit var camera: CameraSource
    private lateinit var audio: AudioPipeline
    private lateinit var stream: StreamController
    private var web: WebOverlaySource? = null
    private var webOutputVisible = false
    private var webState = WebOverlaySource.LoadState.IDLE
    private var webMessage: String? = null
    private var webViewportInfo = ""

    private lateinit var root: FrameLayout
    private lateinit var previewBox: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var editOverlay: EditOverlayView
    private lateinit var statusText: TextView
    private lateinit var webStatusText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var perfText: TextView
    private lateinit var metrics: DeviceMetrics
    private lateinit var runLogger: RunLogger
    private var syncTest = false
    private var lastMetrics: DeviceMetrics.Sample? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var startButton: TextView
    private lateinit var muteButton: TextView
    private lateinit var modeButton: TextView
    private lateinit var panelScroll: ScrollView
    private var currentPanel: String? = null

    private var selectedLayerId: String? = null
    private var interactionMode = false
    private var started = false
    private var pipelineReady = false
    private val bitmapCache = HashMap<String, Bitmap>()

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig.load(this)
        metrics = DeviceMetrics(this)
        runLogger = RunLogger(this)
        applyOrientation()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) initPipeline() else requestPermissions(missing.toTypedArray(), REQ_PERM)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERM) return
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initPipeline()
            if (started) startCapture()
        } else {
            statusText.text = "需要摄像头和麦克风权限才能预览和直播。请在系统设置中授权后重新打开应用。"
        }
    }

    private fun initPipeline() {
        if (pipelineReady) return
        pipelineReady = true
        compositor = Compositor(config.canvasWidth, config.canvasHeight, config.fps)
        camera = CameraSource(this, object : CameraSource.Listener {
            override fun onCameraState(state: CameraSource.State, message: String?) = runOnUiThread {
                cameraMessage = if (message != null) "摄像头：$message" else null
                refreshStatus()
            }
        })
        audio = AudioPipeline(
            onLevel = { lv -> runOnUiThread { levelBar.progress = (lv * 100).toInt() } },
            onError = { msg -> runOnUiThread { micMessage = msg; refreshStatus() } },
        )
        audio.muted = config.micMuted
        stream = StreamController(compositor, audio, object : StreamController.Listener {
            override fun onStats(stats: StreamController.Stats) {
                lastStats = stats; refreshStatus()
                if (stats.durationSec % 5 == 0L) logRow(stats)
            }
            override fun onStreamEnded(error: String?) {
                lastStats = null
                runLogger.finish()?.let { toast("运行日志已保存：$it") }
                streamEndMessage = error
                updateStreamUi()
                refreshStatus()
            }
        })
        compositor.start { st ->
            runOnUiThread { cameraTexture = st; if (started) openCamera() }
        }
        surfaceView.holder.surface?.takeIf { it.isValid }?.let {
            compositor.setPreviewSurface(it, surfaceView.width, surfaceView.height)
        }
        setupWebLayer()
        pushScene()
        registerNetworkCallback()
        mainHandler.post(perfTick)
    }

    /** 网络恢复立即重连；当前网络丢失时主动断开，尽快进入重连（30 秒内恢复的验收项）。 */
    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { mainHandler.post { if (pipelineReady) stream.onNetworkAvailable() } }
            override fun onLost(network: Network) {
                mainHandler.post {
                    if (pipelineReady && cm.activeNetwork == null) stream.onNetworkLost()
                }
            }
        }
        try { cm.registerDefaultNetworkCallback(cb); networkCallback = cb } catch (_: Exception) {}
    }

    // ---- 常驻性能信息：合成帧率、网页采集模式/帧率/耗时、内存、温度 ----
    private var perfLastRendered = 0L
    private var perfLastWebFrames = 0L
    private var perfTicks = 0
    private val perfTick = object : Runnable {
        override fun run() {
            if (!pipelineReady) return
            val rendered = compositor.renderedFrames
            val webFrames = web?.captureFrames ?: 0L
            val outFps = (rendered - perfLastRendered).coerceAtLeast(0)
            val webFps = (webFrames - perfLastWebFrames).coerceAtLeast(0)
            perfLastRendered = rendered; perfLastWebFrames = webFrames
            if (perfTicks++ % 5 == 0) lastMetrics = try { metrics.sample() } catch (_: Exception) { null }
            lastWebFps = webFps.toInt()
            val m = lastMetrics
            perfText.text = buildString {
                append("合成 ${outFps}fps · 网页采集 ${if (config.webCaptureGpu) "GPU" else "CPU"} ${webFps}fps")
                web?.let { append(" ${"%.1f".format(it.lastCaptureMs)}ms") }
                if (m != null) append(" · 内存 ${m.pssMb}MB · 电池 ${"%.1f".format(m.batteryTempC)}°C · 热状态 ${m.thermal}")
                if (syncTest) append(" · 同步测试信号开启")
            }
            mainHandler.postDelayed(this, 1000)
        }
    }
    private var lastWebFps = 0

    private fun logRow(st: StreamController.Stats) {
        val m = lastMetrics
        runLogger.append(listOf(st.durationSec, st.state.name, "%.1f".format(st.outputFps), "%.0f".format(st.bitrateKbps),
            st.droppedFrames, st.renderLate, st.reconnects, "%.1f".format(web?.lastCaptureMs ?: 0.0), lastWebFps,
            m?.pssMb ?: 0, m?.javaMb ?: 0, m?.batteryTempC ?: 0f, m?.thermal ?: ""))
    }

    private fun retryCapture() {
        if (!pipelineReady) return
        cameraMessage = null; micMessage = null
        camera.retry()
        audio.stopCapture(); audio.startCapture()
        if (webState == WebOverlaySource.LoadState.CRASHED || webState == WebOverlaySource.LoadState.ERROR) {
            if (web == null) setupWebLayer() else web?.reload()
        }
        refreshStatus()
    }

    private var cameraTexture: android.graphics.SurfaceTexture? = null
    private var cameraMessage: String? = null
    private var micMessage: String? = null
    private var streamEndMessage: String? = null
    private var lastStats: StreamController.Stats? = null
    private var backgroundStopNotice = false

    override fun onStart() {
        super.onStart()
        started = true
        // 权限可能在后台被撤销（或“仅限这一次”到期）：重新申请
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            if (pipelineReady) requestPermissions(missing.toTypedArray(), REQ_PERM)
            return
        }
        startCapture()
    }

    private fun startCapture() {
        if (!pipelineReady) return
        openCamera()
        micMessage = null
        audio.startCapture()
        if (backgroundStopNotice) {
            backgroundStopNotice = false
            AlertDialog.Builder(this).setMessage("应用切到后台或锁屏时，首版会自动停止推流（避免显示“直播中”但画面已停止）。请重新点击开始推流。")
                .setPositiveButton("知道了", null).show()
        }
    }

    override fun onStop() {
        super.onStop()
        started = false
        if (!pipelineReady) return
        // 首版行为：切后台 / 锁屏即停止推流，并释放摄像头与麦克风
        if (stream.isActive) {
            stream.stop("应用进入后台，推流已停止")
            backgroundStopNotice = true
        }
        camera.close()
        audio.stopCapture()
        config.save(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!pipelineReady) return
        mainHandler.removeCallbacks(perfTick)
        networkCallback?.let { try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        stream.stop()
        web?.release()
        camera.release()
        audio.stopCapture()
        compositor.release()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        root.post { layoutPreview() }
    }

    // ------------------------------------------------------------------ 摄像头

    private fun openCamera() {
        val st = cameraTexture ?: return
        val size = camera.chooseSize(config.resolution.long, config.resolution.short)
        compositor.setCameraGeometry(size.width, size.height, camera.sensorOrientation, cameraRotation())
        camera.open(st, size)
        camera.setZoom(config.zoom)
    }

    /** 摄像头画面需要的旋转（逆时针，度）：补偿屏幕方向 + 用户校正。 */
    private fun cameraRotation(): Int {
        @Suppress("DEPRECATION")
        val r = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (r + config.cameraRotationFix) % 360
    }

    // ------------------------------------------------------------------ 图层 → 合成器

    private fun webLayer(): Layer? = config.layers.firstOrNull { it.type == LayerType.WEB }

    private fun setupWebLayer() {
        web?.release(); web = null
        val l = webLayer() ?: return
        compositor.releaseExternalSurface(l.id)
        val src = WebOverlaySource(this, root, l.viewportW, l.viewportH, config.webCaptureGpu, config.allowCleartextWeb, config.fps,
            object : WebOverlaySource.Listener {
                override fun onLoadStateChanged(state: WebOverlaySource.LoadState, message: String?) {
                    webState = state; webMessage = message; refreshWebStatus()
                }
                override fun onOutputVisibilityChanged(visible: Boolean) { webOutputVisible = visible; pushScene() }
                override fun onViewportMeasured(cssWidth: Int, cssHeight: Int, dpr: Float) {
                    webViewportInfo = "实测视口 ${cssWidth}×$cssHeight CSS px"
                    refreshWebStatus()
                }
            })
        web = src
        compositor.createExternalSurface(l.id, l.viewportW, l.viewportH) { surface ->
            runOnUiThread {
                if (web !== src) return@runOnUiThread
                src.attach(surface)
                if (l.url.isNotBlank()) src.load(l.url)
            }
        }
    }

    private fun pushScene() {
        if (!pipelineReady) return
        val list = ArrayList<Compositor.RenderLayer>()
        for (l in config.layers) {
            if (!l.visible) continue
            when (l.type) {
                LayerType.WEB -> if (webOutputVisible) list.add(Compositor.RenderLayer(l.id, Compositor.RenderLayer.Kind.EXTERNAL, l.x, l.y, l.w, l.h, l.opacity))
                LayerType.IMAGE, LayerType.TEXT -> if (ensureBitmap(l)) list.add(Compositor.RenderLayer(l.id, Compositor.RenderLayer.Kind.BITMAP, l.x, l.y, l.w, l.h, l.opacity))
            }
        }
        compositor.setScene(list)
        editOverlay.invalidate()
    }

    private val bitmapKeys = HashMap<String, String>()

    /** 图片/文字图层的位图（内容变化时重新生成并上传）。 */
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
        bitmapCache[l.id] = bmp
        bitmapKeys[l.id] = key
        compositor.setBitmap(l.id, bmp)
        return true
    }

    private fun loadImage(uri: Uri): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 2048) sample *= 2
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        null
    }

    private fun renderText(l: Layer): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = l.textSizePx; color = l.textColor }
        val lines = l.text.ifEmpty { " " }.split('\n')
        val fm = p.fontMetrics
        val lineH = fm.descent - fm.ascent
        val w = (lines.maxOf { p.measureText(it) } + 8).toInt().coerceAtLeast(8)
        val h = (lineH * lines.size + 8).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        lines.forEachIndexed { i, s -> c.drawText(s, 4f, 4 - fm.ascent + i * lineH, p) }
        return bmp
    }

    // ------------------------------------------------------------------ 推流

    private fun toggleStream() {
        if (!pipelineReady) return
        if (stream.isActive) { stream.stop(); return }
        val url = try {
            if (config.useFullUrl) RtmpUrl.parse(config.fullUrl) else RtmpUrl.fromServerAndKey(config.serverUrl, config.streamKey)
        } catch (e: RtmpUrlException) {
            toast("地址错误：${e.message}"); showPanel("stream"); return
        }
        config.save(this)
        streamEndMessage = null
        runLogger.begin("${config.canvasWidth}x${config.canvasHeight}@${config.fps} ${config.videoBitrateKbps}kbps web=${if (config.webCaptureGpu) "GPU" else "CPU"} ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} ${url.masked()}")
        stream.start(url, config.canvasWidth, config.canvasHeight, config.fps, config.videoBitrateKbps * 1000, config.keyFrameIntervalSec)
        updateStreamUi()
        refreshStatus()
    }

    private fun updateStreamUi() {
        val live = pipelineReady && stream.isActive
        startButton.text = if (live) "■ 停止推流" else "● 开始推流"
        startButton.setBackgroundColor(if (live) Color.rgb(90, 90, 90) else Color.rgb(200, 30, 40))
        // 推流过程中锁定画布方向与编码参数
        if (currentPanel == "stream") showPanel("stream")
    }

    // ------------------------------------------------------------------ 界面

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewBox = FrameLayout(this)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {
                if (pipelineReady) compositor.setPreviewSurface(h.surface, w, hgt)
            }
            override fun surfaceDestroyed(h: SurfaceHolder) {
                if (pipelineReady) compositor.setPreviewSurface(null, 0, 0)
            }
        })
        editOverlay = EditOverlayView(this, editCallback)
        previewBox.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        previewBox.addView(editOverlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(previewBox, FrameLayout.LayoutParams(1, 1))

        // 顶部状态
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.argb(140, 0, 0, 0))
        }
        statusText = label("未开始", 13f)
        webStatusText = label("", 12f, Color.rgb(200, 200, 200))
        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        top.addView(statusText)
        perfText = label("", 11f, Color.rgb(160, 230, 160))
        top.addView(perfText)
        top.addView(webStatusText)
        top.addView(levelBar, LinearLayout.LayoutParams(-1, dp(6)))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // 底部操作栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.argb(160, 0, 0, 0))
        }
        startButton = button("● 开始推流") { toggleStream() }
        muteButton = button(if (config.micMuted) "🔇 已静音" else "🎤 麦克风") { toggleMute() }
        modeButton = button("编辑模式") { toggleMode() }
        bar.addView(startButton)
        bar.addView(muteButton)
        bar.addView(modeButton)
        bar.addView(button("图层") { togglePanel("layers") })
        bar.addView(button("网页") { togglePanel("web") })
        bar.addView(button("推流设置") { togglePanel("stream") })
        bar.addView(button("重试采集") { retryCapture() })
        val barScroll = HorizontalScrollView(this).apply { addView(bar) }
        root.addView(barScroll, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // 设置面板（覆盖在预览上，摄像头/网页/推流继续运行）
        panelScroll = ScrollView(this).apply { background = panelBackground(); visibility = View.GONE }
        root.addView(panelScroll, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(8), dp(70), dp(8), dp(60))
        })

        setContentView(root)
        // targetSdk 35 强制全面屏：内容避开系统栏与挖孔区域
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            v.post { layoutPreview() }
            insets
        }
        root.post { layoutPreview() }
        updateStreamUi()
    }

    /** 预览框按画布比例居中适配屏幕。 */
    private fun layoutPreview() {
        val rw = root.width - root.paddingLeft - root.paddingRight
        val rh = root.height - root.paddingTop - root.paddingBottom
        if (rw == 0 || rh == 0) return
        val cw = config.canvasWidth.toFloat(); val ch = config.canvasHeight.toFloat()
        val s = minOf(rw / cw, rh / ch)
        val w = (cw * s).toInt(); val h = (ch * s).toInt()
        previewBox.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        surfaceView.holder.setFixedSize(w, h)
    }

    private fun refreshStatus() {
        val sb = StringBuilder()
        val st = lastStats
        if (pipelineReady && stream.isActive && st != null) {
            val d = st.durationSec
            sb.append("${st.state.label}  ${"%02d:%02d:%02d".format(d / 3600, d / 60 % 60, d % 60)}")
            sb.append("  ${"%.1f".format(st.outputFps)}fps  ${"%.0f".format(st.bitrateKbps)}kbps")
            sb.append("  丢帧 ${st.droppedFrames}  重连 ${st.reconnects}")
            st.message?.let { sb.append("\n").append(it) }
        } else if (pipelineReady && stream.isActive) {
            sb.append(RtmpPublisher.State.CONNECTING.label)
        } else {
            sb.append("未推流  画布 ${config.canvasWidth}×${config.canvasHeight}@${config.fps}")
            streamEndMessage?.let { sb.append("\n").append(it) }
        }
        cameraMessage?.let { sb.append("\n").append(it) }
        micMessage?.let { sb.append("\n麦克风：").append(it) }
        statusText.text = sb.toString()
    }

    private fun refreshWebStatus() {
        val l = webLayer()
        webStatusText.text = if (l == null) "" else buildString {
            append("网页：${webState.label}")
            if (webViewportInfo.isNotEmpty()) append("（$webViewportInfo）")
            webMessage?.let { append(" · ").append(it) }
            if (webState == WebOverlaySource.LoadState.ERROR || webState == WebOverlaySource.LoadState.CRASHED) append(" · 摄像头推流不受影响")
        }
        webStatusText.setTextColor(if (webState == WebOverlaySource.LoadState.ERROR || webState == WebOverlaySource.LoadState.CRASHED) Color.rgb(255, 120, 120) else Color.rgb(200, 200, 200))
        if (currentPanel == "web") showPanel("web")
    }

    private fun toggleMute() {
        config.micMuted = !config.micMuted
        if (pipelineReady) audio.muted = config.micMuted
        muteButton.text = if (config.micMuted) "🔇 已静音" else "🎤 麦克风"
        config.save(this)
    }

    private fun toggleMode() {
        interactionMode = !interactionMode
        modeButton.text = if (interactionMode) "网页交互模式" else "编辑模式"
        modeButton.setTextColor(if (interactionMode) Color.YELLOW else Color.BLACK)
        if (interactionMode) selectedLayerId = null
        editOverlay.invalidate()
    }

    private fun togglePanel(name: String) {
        if (currentPanel == name) { currentPanel = null; panelScroll.visibility = View.GONE } else showPanel(name)
    }

    private fun showPanel(name: String) {
        currentPanel = name
        panelScroll.removeAllViews()
        val v = when (name) {
            "layers" -> layersPanel()
            "web" -> webPanel()
            else -> streamPanel()
        }
        panelScroll.addView(v)
        panelScroll.visibility = View.VISIBLE
    }

    private fun closePanel() { currentPanel = null; panelScroll.visibility = View.GONE }

    private fun header(title: String): View = row(label(title, 16f), button("关闭") { closePanel() }).apply {
        (getChildAt(1).layoutParams as LinearLayout.LayoutParams).weight = 0f
    }

    // ---- 推流设置 ----
    private fun streamPanel(): View {
        val live = pipelineReady && stream.isActive
        val c = column()
        c.addView(header("推流设置"))
        if (live) c.addView(label("直播中：画布、分辨率、帧率、编码与地址已锁定，停止推流后可修改。", 12f, Color.YELLOW))

        c.addView(label("画布方向"))
        c.addView(spinner(CanvasOrientation.values().toList(), config.orientation.ordinal, { it.label }) {
            config.orientation = it; onCanvasChanged()
        }.apply { isEnabled = !live })
        c.addView(label("输出分辨率（按设备编码能力，首版 720p / 1080p）"))
        val resList = Resolution.values().filter {
            it == config.resolution || (if (config.orientation == CanvasOrientation.LANDSCAPE)
                VideoEncoder.supports(it.long, it.short, config.fps) else VideoEncoder.supports(it.short, it.long, config.fps))
        }
        c.addView(spinner(resList, resList.indexOf(config.resolution), { it.label }) {
            config.resolution = it
            config.videoBitrateKbps = if (it == Resolution.P1080) 4000 else 2500
            onCanvasChanged()
        }.apply { isEnabled = !live })
        val fpsEdit = edit(config.fps.toString(), "帧率", number = true).apply { isEnabled = !live }
        val brEdit = edit(config.videoBitrateKbps.toString(), "码率 kbps", number = true).apply { isEnabled = !live }
        val gopEdit = edit(config.keyFrameIntervalSec.toString(), "关键帧间隔 秒", number = true).apply { isEnabled = !live }
        c.addView(row(label("帧率"), fpsEdit, label("码率kbps"), brEdit, label("GOP秒"), gopEdit))

        c.addView(check("分开填写服务器地址与 Stream Key", !config.useFullUrl) { config.useFullUrl = !it; showPanel("stream") }.apply { isEnabled = !live })
        val fullEdit = edit(config.fullUrl, "rtmp(s)://服务器/应用名/密钥", password = false)
        val serverEdit = edit(config.serverUrl, "rtmp(s)://服务器/应用名")
        val keyEdit = edit(config.streamKey, "Stream Key", password = true)
        if (config.useFullUrl) {
            c.addView(label("完整推流地址"))
            c.addView(fullEdit.apply { isEnabled = !live; if (live) setText(maskedFull()) })
        } else {
            c.addView(label("服务器地址")); c.addView(serverEdit.apply { isEnabled = !live })
            c.addView(label("Stream Key（加密保存，日志中脱敏）")); c.addView(keyEdit.apply { isEnabled = !live })
        }

        c.addView(label("高级 / 技术验证"))
        c.addView(check("网页 GPU 采集（关闭则使用 CPU 软件绘制兜底）", config.webCaptureGpu) {
            config.webCaptureGpu = it; config.save(this); if (pipelineReady) setupWebLayer()
        }.apply { isEnabled = !live })
        c.addView(check("允许局域网 HTTP 明文网页", config.allowCleartextWeb) {
            config.allowCleartextWeb = it; config.save(this); if (pipelineReady) setupWebLayer()
        })
        c.addView(check("音画同步测试信号（左上角每秒闪白 + 1kHz 提示音，替换麦克风声音）", syncTest) {
            syncTest = it
            if (pipelineReady) { compositor.syncTest = it; audio.syncTest = it }
        })
        c.addView(label("摄像头旋转校正（个别机型方向异常时使用）"))
        c.addView(spinner(listOf(0, 90, 180, 270), config.cameraRotationFix / 90, { "$it°" }) {
            config.cameraRotationFix = it; config.save(this); if (pipelineReady) openCamera()
        }.apply { isEnabled = !live })
        if (pipelineReady) {
            c.addView(label("摄像头支持的输出尺寸：" + camera.outputSizes.take(8).joinToString { "${it.width}×${it.height}" }, 11f, Color.LTGRAY))
        }

        if (!live) c.addView(button("保存") {
            config.fps = fpsEdit.text.toString().toIntOrNull()?.coerceIn(15, 30) ?: 30
            config.videoBitrateKbps = brEdit.text.toString().toIntOrNull()?.coerceIn(300, 20000) ?: 4000
            config.keyFrameIntervalSec = gopEdit.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 2
            if (config.useFullUrl) config.fullUrl = fullEdit.text.toString().trim()
            else { config.serverUrl = serverEdit.text.toString().trim(); config.streamKey = keyEdit.text.toString().trim() }
            config.save(this)
            onCanvasChanged()
            toast("已保存")
        })
        return c
    }

    private fun maskedFull(): String = try { RtmpUrl.parse(config.fullUrl).masked() } catch (_: Exception) { "" }

    /** 画布方向/分辨率/帧率变化（仅非直播时）。 */
    private fun onCanvasChanged() {
        val oldW = compositorWidth(); val oldH = compositorHeight()
        val newW = config.canvasWidth; val newH = config.canvasHeight
        if (oldW != newW || oldH != newH) {
            for (l in config.layers) {
                val full = l.x == 0f && l.y == 0f && l.w == oldW.toFloat() && l.h == oldH.toFloat()
                if (full) {
                    l.w = newW.toFloat(); l.h = newH.toFloat()
                    if (l.type == LayerType.WEB) { l.viewportW = newW; l.viewportH = newH }
                } else {
                    l.x = l.x * newW / oldW; l.y = l.y * newH / oldH
                }
            }
        }
        config.save(this)
        applyOrientation()
        if (pipelineReady) {
            compositor.configure(newW, newH, config.fps)
            if (oldW != newW || oldH != newH) setupWebLayer()
            openCamera()
            pushScene()
        }
        root.post { layoutPreview() }
        refreshStatus()
        if (currentPanel != null) showPanel(currentPanel!!)
    }

    private fun compositorWidth() = if (pipelineReady) compositor.canvasWidth else config.canvasWidth
    private fun compositorHeight() = if (pipelineReady) compositor.canvasHeight else config.canvasHeight

    private fun applyOrientation() {
        requestedOrientation = if (config.orientation == CanvasOrientation.LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    // ---- 网页 ----
    private fun webPanel(): View {
        val c = column()
        c.addView(header("网页图层"))
        val l = webLayer()
        if (l == null) {
            c.addView(button("添加网页图层") {
                config.layers.add(Layer(type = LayerType.WEB, name = "网页图层", x = 0f, y = 0f,
                    w = config.canvasWidth.toFloat(), h = config.canvasHeight.toFloat(),
                    viewportW = config.canvasWidth, viewportH = config.canvasHeight))
                config.save(this); setupWebLayer(); showPanel("web")
            })
            return c
        }
        c.addView(label("状态：${webState.label}${webMessage?.let { " · $it" } ?: ""}", 13f))
        if (webViewportInfo.isNotEmpty()) c.addView(label(webViewportInfo, 12f, Color.LTGRAY))
        val urlEdit = edit(l.url, "https://... 透明背景网页")
        c.addView(label("网页 URL")); c.addView(urlEdit)
        val vw = edit(l.viewportW.toString(), "宽", number = true)
        val vh = edit(l.viewportH.toString(), "高", number = true)
        c.addView(label("渲染视口（网页布局尺寸，CSS 像素；与图层在画布中的显示尺寸相互独立）"))
        c.addView(row(label("宽"), vw, label("高"), vh))
        c.addView(row(
            button("加载 / 应用") {
                val newUrl = urlEdit.text.toString().trim()
                val nvw = vw.text.toString().toIntOrNull()?.coerceIn(64, 3840) ?: l.viewportW
                val nvh = vh.text.toString().toIntOrNull()?.coerceIn(64, 3840) ?: l.viewportH
                val viewportChanged = nvw != l.viewportW || nvh != l.viewportH
                l.url = newUrl; l.viewportW = nvw; l.viewportH = nvh
                config.save(this)
                if (viewportChanged || web == null) setupWebLayer() else web?.load(newUrl)
            },
            button("刷新") { if (web == null) setupWebLayer() else web?.reload() },
            button("测试页") { urlEdit.setText("file:///android_asset/testpage/index.html") },
        ))
        c.addView(label("提示：拖动/缩放请用“编辑模式”；需要点击网页里的按钮时切换到“网页交互模式”。", 12f, Color.LTGRAY))
        return c
    }

    // ---- 图层 ----
    private fun layersPanel(): View {
        val c = column()
        c.addView(header("图层（列表上方 = 画面最上层）"))
        c.addView(row(
            button("+ 图片") { pickImage() },
            button("+ 文字") { addTextLayer() },
        ))
        for (l in config.layers.reversed()) {
            val sel = l.id == selectedLayerId
            val name = label("${if (sel) "▶ " else ""}${l.type.label}·${l.name}${if (!l.visible) "（隐藏）" else ""}${if (l.locked) "🔒" else ""}", 14f,
                if (sel) Color.rgb(0, 200, 255) else Color.WHITE).apply {
                setOnClickListener { selectedLayerId = if (sel) null else l.id; editOverlay.invalidate(); showPanel("layers") }
                setPadding(0, dp(8), 0, dp(8))
            }
            c.addView(name)
            c.addView(row(
                button(if (l.visible) "隐藏" else "显示") { l.visible = !l.visible; layerEdited(); showPanel("layers") },
                button(if (l.locked) "解锁" else "锁定") { l.locked = !l.locked; layerEdited(); showPanel("layers") },
                button("上移") { moveLayer(l, +1) },
                button("下移") { moveLayer(l, -1) },
                button("删除") { deleteLayer(l) },
            ))
            if (sel) c.addView(layerDetail(l))
        }
        return c
    }

    private fun layerDetail(l: Layer): View {
        val c = column()
        val x = edit("%.0f".format(l.x), "X", number = true)
        val y = edit("%.0f".format(l.y), "Y", number = true)
        val w = edit("%.0f".format(l.w), "宽", number = true)
        val h = edit("%.0f".format(l.h), "高", number = true)
        val op = edit("%.0f".format(l.opacity * 100), "不透明度%", number = true)
        c.addView(row(label("X"), x, label("Y"), y))
        c.addView(row(label("宽"), w, label("高"), h))
        c.addView(row(label("图层不透明度%"), op))
        var textEdit: TextView? = null
        var sizeEdit: TextView? = null
        var colorEdit: TextView? = null
        if (l.type == LayerType.TEXT) {
            textEdit = edit(l.text, "文字内容").also { c.addView(it) }
            sizeEdit = edit("%.0f".format(l.textSizePx), "字号 px", number = true)
            colorEdit = edit(String.format("#%08X", l.textColor), "#AARRGGBB")
            c.addView(row(label("字号"), sizeEdit, label("颜色"), colorEdit))
        }
        c.addView(row(
            button("应用") {
                l.x = x.text.toString().toFloatOrNull() ?: l.x
                l.y = y.text.toString().toFloatOrNull() ?: l.y
                l.w = (w.text.toString().toFloatOrNull() ?: l.w).coerceAtLeast(8f)
                l.h = (h.text.toString().toFloatOrNull() ?: l.h).coerceAtLeast(8f)
                l.opacity = ((op.text.toString().toFloatOrNull() ?: (l.opacity * 100)) / 100f).coerceIn(0f, 1f)
                if (l.type == LayerType.TEXT) {
                    val oldSize = l.textSizePx
                    l.text = textEdit!!.text.toString()
                    l.textSizePx = sizeEdit!!.text.toString().toFloatOrNull()?.coerceIn(8f, 400f) ?: l.textSizePx
                    l.textColor = try { Color.parseColor(colorEdit!!.text.toString().trim()) } catch (_: Exception) { l.textColor }
                    val bmp = renderText(l)
                    if (oldSize != l.textSizePx || l.w <= 8f) { l.w = bmp.width.toFloat(); l.h = bmp.height.toFloat() }
                }
                layerEdited(); showPanel("layers")
            },
            button("铺满画布") {
                l.x = 0f; l.y = 0f; l.w = config.canvasWidth.toFloat(); l.h = config.canvasHeight.toFloat()
                layerEdited(); showPanel("layers")
            },
            button("居中") {
                l.x = (config.canvasWidth - l.w) / 2; l.y = (config.canvasHeight - l.h) / 2
                layerEdited(); showPanel("layers")
            },
        ))
        return c
    }

    private fun layerEdited() { pushScene(); config.save(this) }

    private fun moveLayer(l: Layer, delta: Int) {
        val i = config.layers.indexOf(l)
        val j = (i + delta).coerceIn(0, config.layers.size - 1)
        if (i == j) return
        config.layers.removeAt(i); config.layers.add(j, l)
        layerEdited(); showPanel("layers")
    }

    private fun deleteLayer(l: Layer) {
        AlertDialog.Builder(this).setMessage("删除图层“${l.name}”？")
            .setPositiveButton("删除") { _, _ ->
                config.layers.remove(l)
                if (selectedLayerId == l.id) selectedLayerId = null
                if (l.type == LayerType.WEB) {
                    web?.release(); web = null; webOutputVisible = false
                    webState = WebOverlaySource.LoadState.IDLE; webMessage = null
                    if (pipelineReady) compositor.releaseExternalSurface(l.id)
                    refreshWebStatus()
                } else if (pipelineReady) {
                    compositor.removeBitmap(l.id); bitmapKeys.remove(l.id); bitmapCache.remove(l.id)
                }
                layerEdited(); showPanel("layers")
            }.setNegativeButton("取消", null).show()
    }

    private fun pickImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
        startActivityForResult(i, REQ_IMAGE)
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val bmp = loadImage(uri) ?: run { toast("无法读取图片"); return }
        val maxW = config.canvasWidth / 3f
        val scale = minOf(1f, maxW / bmp.width)
        val l = Layer(type = LayerType.IMAGE, name = "图片${config.layers.count { it.type == LayerType.IMAGE } + 1}",
            x = 40f, y = 40f, w = bmp.width * scale, h = bmp.height * scale, imageUri = uri.toString())
        config.layers.add(l)
        selectedLayerId = l.id
        layerEdited(); showPanel("layers")
    }

    private fun addTextLayer() {
        val l = Layer(type = LayerType.TEXT, name = "文字${config.layers.count { it.type == LayerType.TEXT } + 1}",
            x = 60f, y = 60f, w = 0f, h = 0f, text = "直播合成 LiveComposer", textSizePx = config.canvasHeight / 16f)
        val bmp = renderText(l)
        l.w = bmp.width.toFloat(); l.h = bmp.height.toFloat()
        config.layers.add(l)
        selectedLayerId = l.id
        layerEdited(); showPanel("layers")
    }

    private val editCallback = object : EditOverlayView.Callback {
        override fun layers(): List<Layer> = config.layers
        override fun canvasSize() = config.canvasWidth to config.canvasHeight
        override fun interactionMode() = interactionMode
        override fun selectedId() = selectedLayerId
        override fun onSelect(id: String?) {
            selectedLayerId = id
            editOverlay.invalidate()
            if (currentPanel == "layers") showPanel("layers")
        }
        override fun onLayerChanged(layer: Layer, finished: Boolean) {
            pushScene()
            if (finished) { config.save(this@MainActivity); if (currentPanel == "layers") showPanel("layers") }
        }
        override fun onTapFocus(canvasX: Float, canvasY: Float) {
            if (!pipelineReady) return
            compositor.canvasToCameraUv(canvasX, canvasY)?.let { (u, v) -> camera.focusAt(u, v) }
        }
        override fun onCameraPinch(scale: Float) {
            if (!pipelineReady) return
            config.zoom = (config.zoom * scale).coerceIn(camera.zoomRange.lower, camera.zoomRange.upper)
            camera.setZoom(config.zoom)
        }
        override fun onWebTouch(ev: MotionEvent, canvasX: Float, canvasY: Float): Boolean {
            val l = webLayer() ?: return false
            val src = web ?: return false
            // 画布坐标 → 网页视口坐标
            val vx = (canvasX - l.x) / l.w * l.viewportW
            val vy = (canvasY - l.y) / l.h * l.viewportH
            src.dispatchTouch(ev, vx, vy)
            return true
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PERM = 1
        private const val REQ_IMAGE = 2
    }
}
