package app.livecomposer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import app.livecomposer.core.CanvasOrientation
import app.livecomposer.engine.EngineState
import app.livecomposer.engine.StudioEngine

/**
 * 唯一的 Activity：负责权限、生命周期、方向，把引擎（StudioEngine）和 Compose 界面（StudioScreen）接起来。
 *
 * 视图层级：root FrameLayout
 *   ├─ webParent：离屏网页的宿主（挂在窗口上但不在屏幕上绘制，见 WebOverlaySource）
 *   └─ ComposeView：全部可见界面
 */
class StudioActivity : ComponentActivity() {

    private lateinit var engine: StudioEngine
    private val state = mutableStateOf<EngineState?>(null)
    private val ui = StudioUi()

    private var pendingSurface: Surface? = null
    private var pendingW = 0
    private var pendingH = 0
    private var initialized = false
    private var started = false
    /** 选本地视频前在“添加来源”里选的循环设置。 */
    private var pickLoop = true

    // ---- 省电：直播中一段时间无操作自动调暗屏幕，触摸恢复
    private val main = Handler(Looper.getMainLooper())
    private var dimmed = false
    private val dimTask = Runnable { maybeDim() }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPermissions()) initEngine()
        else engine.notify("需要摄像头和麦克风权限才能采集与推流")
    }

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        engine.addMediaLayer(uri.toString(), live = false, loop = pickLoop)?.let { ui.selectedId = it.id }
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        engine.addImageLayer(uri)?.let { ui.selectedId = it.id; ui.panel = Panel.INSPECTOR }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferLowRefreshRate()

        val root = FrameLayout(this)
        val webParent = FrameLayout(this)
        root.addView(webParent, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val compose = ComposeView(this)
        root.addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        engine = StudioEngine(this, webParent)
        engine.onChanged = {
            val s = engine.snapshot()
            state.value = s
            if (dimmed && !s.isStreaming) { undim(); scheduleDim() } // 停播后恢复亮度
        }
        state.value = engine.snapshot()
        applyOrientation()

        val cb = buildCallbacks()
        compose.setContent { StudioTheme { StudioScreen(state.value, ui, cb) } }

        if (hasPermissions()) initEngine()
        else permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun hasPermissions() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun initEngine() {
        if (initialized) return
        initialized = true
        engine.init(pendingSurface, pendingW, pendingH)
        if (started) engine.onStart()
    }

    private fun applyOrientation() {
        val want = if (engine.config.orientation == CanvasOrientation.LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        if (requestedOrientation != want) requestedOrientation = want
    }

    private fun indexOf(id: String) = engine.config.layers.indexOfFirst { it.id == id }

    private fun buildCallbacks(): StudioCallbacks {
        val stage = StageActions(
            onSurface = { s, w, h ->
                pendingSurface = s; pendingW = w; pendingH = h
                if (initialized) engine.setPreviewSurface(s, w, h)
            },
            onSelect = { id -> ui.selectedId = id },
            onLayerChange = { layer, persist -> engine.updateLayer(layer, persist) },
            onFocus = { x, y -> engine.focusAt(x, y) },
            onZoom = { z -> engine.setZoom(engine.config.zoom * z) },
            onWebTouch = { ev, x, y -> engine.dispatchWebTouch(ev, x, y) },
            onInspect = { ui.panel = Panel.INSPECTOR },
            onRaise = { id -> val i = indexOf(id); if (i >= 0) engine.moveLayer(id, i + 1) },
            onLower = { id -> val i = indexOf(id); if (i > 0) engine.moveLayer(id, i - 1) },
            onDelete = { id -> ui.confirmDelete = id },
            onReloadWeb = { engine.reloadWeb() },
            onReloadMedia = { id -> engine.reloadMedia(id) },
        )
        return StudioCallbacks(
            stage = stage,
            onGoLive = { engine.startStream() },
            onStopLive = { engine.stopStream() },
            validateUrl = { engine.validateUrl() },
            onToggleMute = { engine.toggleMute() },
            onUpdateLayer = { engine.updateLayer(it, true) },
            onFitText = { engine.fitTextLayer(it) },
            onWeb = { url, vw, vh -> engine.updateWeb(url, vw, vh) },
            onAddWeb = { url -> engine.addWebLayer(url)?.id },
            onPickImage = { imagePicker.launch(arrayOf("image/*")) },
            onAddText = { text -> engine.addTextLayer(text).id },
            onApply = { edit -> engine.applySettings(edit); if (ui.urlError != null) ui.urlError = engine.validateUrl(); applyOrientation() },
            onSyncTest = { engine.setSyncTest(it) },
            onRetry = { engine.retryCapture() },
            onConsumeNotice = { engine.consumeNotice() },
            onDeleteLayer = { id -> engine.deleteLayer(id); if (ui.selectedId == id) ui.selectedId = null },
            onAddMedia = { uri, live, loop -> engine.addMediaLayer(uri, live, loop)?.id },
            onPickVideo = { loop -> pickLoop = loop; videoPicker.launch(arrayOf("video/*")) },
            onMedia = { id, uri, loop -> engine.updateMedia(id, uri, loop) },
            scenes = SceneActions(
                onSave = { name -> engine.saveScene(name) },
                onOverwrite = { id -> engine.overwriteScene(id) },
                onApply = { id -> engine.applyScene(id); applyOrientation() },
                onRename = { id, name -> engine.renameScene(id, name) },
                onDelete = { id -> engine.deleteScene(id) },
                onMove = { id, d -> engine.moveScene(id, d) },
            ),
        )
    }

    /**
     * 120Hz / 90Hz 屏幕降到 60Hz：输出最高 60fps，界面和预览没必要按高刷绘制，屏幕和 GPU 都更省电。
     * 只选与当前分辨率相同的模式；系统或厂商不支持时忽略。
     */
    private fun preferLowRefreshRate() {
        try {
            @Suppress("DEPRECATION")
            val d = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
            d ?: return
            val cur = d.mode
            val mode = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight && it.refreshRate >= 59f }
                .minByOrNull { it.refreshRate } ?: return
            if (mode.modeId != cur.modeId) window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
        } catch (_: Exception) {
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val wasDimmed = dimmed
            undim()
            scheduleDim()
            if (wasDimmed) return true // 唤醒屏幕的这次触摸不当作操作，避免误触
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun scheduleDim() {
        main.removeCallbacks(dimTask)
        main.postDelayed(dimTask, DIM_AFTER_MS)
    }

    private fun maybeDim() {
        val s = state.value
        if (initialized && started && engine.config.powerSave && s != null && s.isStreaming && !dimmed) {
            dimmed = true
            window.attributes = window.attributes.apply { screenBrightness = 0.02f }
            engine.notify("已调暗屏幕省电，直播不受影响；触摸屏幕恢复")
        } else if (!dimmed) scheduleDim()
    }

    private fun undim() {
        if (!dimmed) return
        dimmed = false
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    override fun onStart() {
        super.onStart()
        scheduleDim()
        started = true
        if (!initialized && hasPermissions()) initEngine() else if (initialized) engine.onStart()
    }

    override fun onStop() {
        started = false
        main.removeCallbacks(dimTask)
        undim()
        if (initialized && engine.onStop()) ui.backgroundStopped = true
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (initialized) engine.onDisplayRotationChanged()
    }

    companion object {
        private const val DIM_AFTER_MS = 45_000L
    }

    override fun onDestroy() {
        if (initialized) engine.release()
        super.onDestroy()
    }
}
