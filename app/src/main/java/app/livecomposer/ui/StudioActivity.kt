package app.livecomposer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
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
        engine.addMediaLayer(uri.toString(), live = false)?.let { ui.selectedId = it.id }
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

        val root = FrameLayout(this)
        val webParent = FrameLayout(this)
        root.addView(webParent, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val compose = ComposeView(this)
        root.addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        engine = StudioEngine(this, webParent)
        engine.onChanged = { state.value = engine.snapshot() }
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
            onAddMedia = { uri, live -> engine.addMediaLayer(uri, live)?.id },
            onPickVideo = { videoPicker.launch(arrayOf("video/*")) },
            onMedia = { id, uri, loop -> engine.updateMedia(id, uri, loop) },
        )
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (!initialized && hasPermissions()) initEngine() else if (initialized) engine.onStart()
    }

    override fun onStop() {
        started = false
        if (initialized && engine.onStop()) ui.backgroundStopped = true
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (initialized) engine.onDisplayRotationChanged()
    }

    override fun onDestroy() {
        if (initialized) engine.release()
        super.onDestroy()
    }
}
