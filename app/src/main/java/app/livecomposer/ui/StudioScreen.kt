package app.livecomposer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.livecomposer.core.AppConfig
import app.livecomposer.core.Layer
import app.livecomposer.engine.EngineState
import app.livecomposer.engine.LiveState

enum class Panel { NONE, LAYERS, INSPECTOR, ADD, SETTINGS, SCENES, CAMSTREAM }

/** 界面对引擎的全部操作。 */
class StudioCallbacks(
    val stage: StageActions,
    val onGoLive: () -> String?,
    val onStopLive: () -> Unit,
    val validateUrl: () -> String?,
    val onToggleMute: () -> Unit,
    val onUpdateLayer: (Layer) -> Unit,
    val onFitText: (String) -> Unit,
    val onWeb: (String, Int, Int) -> Unit,
    val onAddWeb: (String) -> String?,
    val onPickImage: () -> Unit,
    val onAddText: (String) -> String?,
    val onApply: ((AppConfig) -> Unit) -> Unit,
    val onSyncTest: (Boolean) -> Unit,
    val onRetry: () -> Unit,
    val onConsumeNotice: () -> Unit,
    val onDeleteLayer: (String) -> Unit,
    /** 添加媒体图层：(地址, 是否拉流, 是否循环) */
    val onAddMedia: (String, Boolean, Boolean) -> String?,
    /** 选择本地视频，参数为是否循环。 */
    val onPickVideo: (Boolean) -> Unit,
    val onMedia: (String, String, Boolean) -> Unit,
    val scenes: SceneActions,
    val camStream: CamStreamActions,
)

class StudioUi {
    var selectedId by mutableStateOf<String?>(null)
    var panel by mutableStateOf(Panel.NONE)
    var interaction by mutableStateOf(false)
    var aspectLock by mutableStateOf(true)
    var showPerf by mutableStateOf(true)
    var urlError by mutableStateOf<String?>(null)
    var confirmStop by mutableStateOf(false)
    var confirmDelete by mutableStateOf<String?>(null)
    var confirmDeleteScene by mutableStateOf<String?>(null)
    var backgroundStopped by mutableStateOf(false)
}

@Composable
fun StudioScreen(state: EngineState?, ui: StudioUi, cb: StudioCallbacks) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Studio.Bg)) {
        if (state == null) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("直播合成", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Studio.Text)
                Spacer(Modifier.height(8.dp))
                Text("需要摄像头和麦克风权限才能预览和直播", color = Studio.TextDim, fontSize = 14.sp)
            }
            return@BoxWithConstraints
        }
        val landscape = maxWidth > maxHeight
        val selected = state.layers.firstOrNull { it.id == ui.selectedId }
        if (ui.selectedId != null && selected == null) ui.selectedId = null
        if (ui.panel == Panel.INSPECTOR && selected == null) ui.panel = Panel.NONE

        val goLive: () -> Unit = {
            if (state.isStreaming) ui.confirmStop = true
            else {
                val err = cb.validateUrl() ?: cb.onGoLive()
                ui.urlError = err
                if (err != null) ui.panel = Panel.SETTINGS
            }
        }

        if (landscape) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    StageArea(state, ui, cb, Modifier.fillMaxSize().padding(8.dp))
                    Hud(state, ui, cb, Modifier.align(Alignment.TopStart).padding(16.dp))
                    PanelHost(state, ui, cb, landscape = true, modifier = Modifier.align(Alignment.CenterEnd))
                }
                Rail(state, ui, goLive, cb)
            }
        } else {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Hud(state, ui, cb, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    StageArea(state, ui, cb, Modifier.fillMaxSize().padding(horizontal = 8.dp))
                    PanelHost(state, ui, cb, landscape = false, modifier = Modifier.align(Alignment.BottomCenter))
                }
                Dock(state, ui, goLive, cb)
            }
        }

        Toast(state.notice, cb.onConsumeNotice, Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp))
        Dialogs(state, ui, cb)
    }
}

@Composable
private fun StageArea(state: EngineState, ui: StudioUi, cb: StudioCallbacks, modifier: Modifier) {
    Box(modifier) {
        Stage(state, ui.selectedId, ui.interaction, ui.aspectLock, cb.stage, Modifier.fillMaxSize())
        AnimatedVisibility(ui.interaction, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Studio.Accent).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.TouchApp, null, tint = Color(0xFF00202A), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("网页交互模式：触摸直接操作网页", color = Color(0xFF00202A), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("退出", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF00202A)).clickable { ui.interaction = false }
                        .padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- 顶部状态

@Composable
private fun Hud(state: EngineState, ui: StudioUi, cb: StudioCallbacks, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SceneChip(state, ui)
            CamStreamChip(state, ui.panel == Panel.CAMSTREAM) { ui.togglePanel(Panel.CAMSTREAM) }
            val d = state.durationSec
            val time = "%02d:%02d:%02d".format(d / 3600, d / 60 % 60, d % 60)
            when (state.live) {
                LiveState.IDLE -> Pill("未开播 · ${state.canvasWidth}×${state.canvasHeight} · ${state.fps}fps", Studio.TextDim, filled = false)
                LiveState.CONNECTING -> Pill("连接中…", Studio.Warn, filled = false, pulsing = true)
                LiveState.LIVE -> Pill("LIVE  $time", Studio.Live, filled = true)
                LiveState.RECONNECTING -> Pill("重连中 · $time", Studio.Warn, filled = false, pulsing = true)
                LiveState.FAILED -> Pill("推流失败", Studio.Live, filled = false)
            }
            if (state.isStreaming) {
                GlassChip("%.1f fps".format(state.outFps), if (state.outFps >= state.fps - 2) Studio.Text else Studio.Warn)
                GlassChip("%.0f kbps".format(state.bitrateKbps))
                GlassChip("丢帧 ${state.droppedFrames}", if (state.droppedFrames > 0) Studio.Warn else Studio.Text)
                if (state.reconnects > 0) GlassChip("重连 ${state.reconnects}", Studio.Warn)
            }
            if (state.muted) GlassChip("麦克风已静音", Studio.Live)
        }
        if (ui.showPerf) {
            val m = state.metrics
            GlassChip(buildString {
                append("合成 ${state.renderFps}fps · 网页 ${if (state.webGpu) "GPU" else "CPU"} ${state.webFps}fps ${"%.1f".format(state.webCaptureMs)}ms")
                if (m != null) append(" · ${m.pssMb}MB · ${"%.1f".format(m.batteryTempC)}°C ${m.thermal}")
                if (state.syncTest) append(" · 同步测试")
            }, Studio.TextDim)
        }
        val problems = listOfNotNull(state.streamMessage, state.cameraMessage?.let { "摄像头：$it" }, state.micMessage?.let { "麦克风：$it" })
        problems.forEach { msg ->
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(Studio.Glass).border(1.dp, Studio.Warn.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(msg, color = Studio.Text, fontSize = 12.sp, modifier = Modifier.weight(1f, fill = false))
                if (msg != state.streamMessage) {
                    Text("重试", color = Studio.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { cb.onRetry() }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }
}

/** 顶部的场景入口：显示当前场景名，点开场景面板快速切换。 */
@Composable
private fun SceneChip(state: EngineState, ui: StudioUi) {
    val name = state.scenes.firstOrNull { it.id == state.activeSceneId }?.name ?: "未保存的场景"
    val active = ui.panel == Panel.SCENES
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (active) Studio.AccentDim else Studio.Glass)
            .border(1.dp, if (active) Studio.Accent else Studio.Outline, RoundedCornerShape(50))
            .clickable { ui.togglePanel(Panel.SCENES) }
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Collections, null, tint = Studio.Accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(name, color = Studio.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.widthIn(max = 140.dp))
        Spacer(Modifier.width(2.dp))
        Icon(Icons.Rounded.ExpandMore, null, tint = Studio.TextDim, modifier = Modifier.size(14.dp))
    }
}

// ---------------------------------------------------------------- 工具栏

/**
 * 横屏右侧竖排工具栏。开播和麦克风固定在底部，始终可见；
 * 上半部分放设置、图层、添加，次要入口（网页交互模式、性能信息）收进“更多”，高度不够时上半部分可滚动。
 */
@Composable
private fun Rail(state: EngineState, ui: StudioUi, goLive: () -> Unit, cb: StudioCallbacks) {
    Column(
        Modifier.width(84.dp).fillMaxHeight().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoundIconButton(Icons.Rounded.Settings, "设置", { ui.togglePanel(Panel.SETTINGS) }, active = ui.panel == Panel.SETTINGS, size = 42.dp)
            RoundIconButton(Icons.Rounded.Layers, "图层", { ui.togglePanel(Panel.LAYERS) }, active = ui.panel == Panel.LAYERS, size = 42.dp)
            RoundIconButton(Icons.Rounded.Add, "添加", { ui.togglePanel(Panel.ADD) }, active = ui.panel == Panel.ADD, size = 42.dp)
            MoreButton(ui, size = 42.dp)
        }
        Spacer(Modifier.height(8.dp))
        MicButton(state, cb.onToggleMute)
        Spacer(Modifier.height(8.dp))
        GoLiveButton(state, goLive, compact = true)
    }
}

/** “更多”：网页交互模式、性能信息开关。 */
@Composable
private fun MoreButton(ui: StudioUi, size: androidx.compose.ui.unit.Dp = 44.dp) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundIconButton(Icons.Rounded.MoreHoriz, "更多", { open = true },
            active = open || ui.interaction, size = size)
        DropdownMenu(open, { open = false }, Modifier.background(Studio.SurfaceHigher)) {
            MenuRow(Icons.Rounded.TouchApp, if (ui.interaction) "退出网页交互" else "网页交互模式", ui.interaction) {
                open = false; ui.interaction = !ui.interaction; ui.selectedId = null
            }
            MenuRow(Icons.Rounded.Speed, if (ui.showPerf) "隐藏性能信息" else "显示性能信息", ui.showPerf) { open = false; ui.showPerf = !ui.showPerf }
        }
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = if (active) Studio.Accent else Studio.Text, fontSize = 14.sp) },
        leadingIcon = { Icon(icon, null, tint = if (active) Studio.Accent else Studio.TextDim) },
        onClick = onClick,
    )
}

@Composable
private fun Dock(state: EngineState, ui: StudioUi, goLive: () -> Unit, cb: StudioCallbacks) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        RoundIconButton(Icons.Rounded.Layers, "图层", { ui.togglePanel(Panel.LAYERS) }, active = ui.panel == Panel.LAYERS)
        RoundIconButton(Icons.Rounded.Add, "添加", { ui.togglePanel(Panel.ADD) }, active = ui.panel == Panel.ADD)
        GoLiveButton(state, goLive)
        MicButton(state, cb.onToggleMute)
        RoundIconButton(Icons.Rounded.Settings, "设置", { ui.togglePanel(Panel.SETTINGS) }, active = ui.panel == Panel.SETTINGS)
        MoreButton(ui)
    }
}

fun StudioUi.togglePanel(p: Panel) { panel = if (panel == p) Panel.NONE else p }

/** 麦克风按钮：外圈显示实时电平，静音为红色。 */
@Composable
private fun MicButton(state: EngineState, onToggle: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Studio.SurfaceHigh).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(44.dp)) {
                val sw = 3.dp.toPx()
                drawArc(Studio.Outline, 0f, 360f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw))
                if (!state.muted) drawArc(Studio.Ok, -90f, 360f * state.micLevel, false, Offset(sw / 2, sw / 2),
                    Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
            }
            Icon(if (state.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, "麦克风",
                tint = if (state.muted) Studio.Live else Studio.Text, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(if (state.muted) "已静音" else "麦克风", fontSize = 10.sp, color = if (state.muted) Studio.Live else Studio.TextDim)
    }
}

/** 开播 / 停播按钮。 */
@Composable
private fun GoLiveButton(state: EngineState, onClick: () -> Unit, compact: Boolean = false) {
    val live = state.isStreaming
    val pulse = if (live) {
        rememberInfiniteTransition(label = "live").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p").value
    } else 1f
    val color = when (state.live) {
        LiveState.CONNECTING, LiveState.RECONNECTING -> Studio.Warn
        else -> Studio.Live
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(if (compact) 60.dp else 68.dp).clip(CircleShape).border(3.dp, color.copy(alpha = if (live) pulse else 1f), CircleShape)
                .clickable(onClick = onClick).padding(7.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (live) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(color))
            } else {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    Text("开播", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        val d = state.durationSec
        Text(
            if (live) "%02d:%02d".format(d / 60, d % 60) else "开始直播",
            fontSize = 10.sp, color = if (live) color else Studio.TextDim, fontFamily = if (live) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

// ---------------------------------------------------------------- 面板容器

@Composable
private fun PanelHost(state: EngineState, ui: StudioUi, cb: StudioCallbacks, landscape: Boolean, modifier: Modifier) {
    val visible = ui.panel != Panel.NONE
    val panelModifier = if (landscape) Modifier.width(360.dp).fillMaxHeight().padding(8.dp)
    else Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(8.dp)
    AnimatedVisibility(
        visible, modifier,
        enter = if (landscape) slideInHorizontally { it } + fadeIn() else slideInVertically { it } + fadeIn(),
        exit = if (landscape) slideOutHorizontally { it } + fadeOut() else slideOutVertically { it } + fadeOut(),
    ) {
        val close = { ui.panel = Panel.NONE }
        val layers = state.layers
        when (ui.panel) {
            Panel.LAYERS -> LayersPanel(state, ui.selectedId, { ui.selectedId = it }, cb.onUpdateLayer,
                { id -> ui.selectedId = id; ui.panel = Panel.INSPECTOR }, { ui.panel = Panel.ADD }, close, panelModifier)
            Panel.INSPECTOR -> {
                val l = layers.firstOrNull { it.id == ui.selectedId }
                if (l != null) InspectorPanel(
                    state, l, ui.aspectLock, { ui.aspectLock = it }, cb.onUpdateLayer, cb.onFitText, cb.onWeb,
                    cb.stage.onReloadWeb, cb.onMedia, cb.stage.onReloadMedia, cb.onApply, { cb.stage.onRaise(l.id) }, { cb.stage.onLower(l.id) }, { ui.confirmDelete = l.id }, close, panelModifier,
                )
            }
            Panel.ADD -> AddPanel(state,
                onAddStream = { url -> cb.onAddMedia(url, true, false)?.let { ui.selectedId = it; ui.panel = Panel.NONE } },
                onAddRemoteVideo = { url, loop -> cb.onAddMedia(url, false, loop)?.let { ui.selectedId = it; ui.panel = Panel.NONE } },
                onPickVideo = { loop -> cb.onPickVideo(loop); ui.panel = Panel.NONE },
                onAddWeb = { url -> cb.onAddWeb(url)?.let { ui.selectedId = it }; ui.panel = Panel.NONE },
                onPickImage = { cb.onPickImage(); ui.panel = Panel.NONE },
                onAddText = { t -> cb.onAddText(t)?.let { ui.selectedId = it; ui.panel = Panel.INSPECTOR } },
                onClose = close, modifier = panelModifier)
            Panel.SETTINGS -> SettingsPanel(state, ui.urlError, { edit -> cb.onApply(edit); ui.urlError = null },
                cb.onSyncTest, ui.showPerf, { ui.showPerf = it }, close, panelModifier)
            Panel.SCENES -> ScenesPanel(state, SceneActions(
                onSave = cb.scenes.onSave, onOverwrite = cb.scenes.onOverwrite,
                onApply = { id -> ui.selectedId = null; cb.scenes.onApply(id) },
                onRename = cb.scenes.onRename, onDelete = { id -> ui.confirmDeleteScene = id }, onMove = cb.scenes.onMove,
            ), close, panelModifier)
            Panel.CAMSTREAM -> CamStreamPanel(state, cb.camStream, close, panelModifier)
            Panel.NONE -> {}
        }
    }
}

// ---------------------------------------------------------------- 对话框

@Composable
private fun Dialogs(state: EngineState, ui: StudioUi, cb: StudioCallbacks) {
    if (ui.confirmStop) {
        AlertDialog(
            onDismissRequest = { ui.confirmStop = false },
            containerColor = Studio.SurfaceHigh,
            title = { Text("结束直播？", color = Studio.Text) },
            text = { Text("已直播 ${state.durationSec / 60} 分 ${state.durationSec % 60} 秒。结束后可以随时重新开播。", color = Studio.TextDim) },
            confirmButton = { TextButton({ ui.confirmStop = false; cb.onStopLive() }) { Text("结束直播", color = Studio.Live, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton({ ui.confirmStop = false }) { Text("继续直播", color = Studio.Text) } },
        )
    }
    ui.confirmDelete?.let { id ->
        val l = state.layers.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { ui.confirmDelete = null },
            containerColor = Studio.SurfaceHigh,
            title = { Text("删除图层？", color = Studio.Text) },
            text = { Text("“${l?.name ?: ""}”将从画面中移除。", color = Studio.TextDim) },
            confirmButton = { TextButton({ ui.confirmDelete = null; cb.onDeleteLayer(id) }) { Text("删除", color = Studio.Live, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton({ ui.confirmDelete = null }) { Text("取消", color = Studio.Text) } },
        )
    }
    ui.confirmDeleteScene?.let { id ->
        val sc = state.scenes.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { ui.confirmDeleteScene = null },
            containerColor = Studio.SurfaceHigh,
            title = { Text("删除场景？", color = Studio.Text) },
            text = { Text("“${sc?.name ?: ""}”将被删除，当前画面不受影响。", color = Studio.TextDim) },
            confirmButton = { TextButton({ ui.confirmDeleteScene = null; cb.scenes.onDelete(id) }) { Text("删除", color = Studio.Live, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton({ ui.confirmDeleteScene = null }) { Text("取消", color = Studio.Text) } },
        )
    }
    if (ui.backgroundStopped) {
        AlertDialog(
            onDismissRequest = { ui.backgroundStopped = false },
            containerColor = Studio.SurfaceHigh,
            title = { Text("直播已停止", color = Studio.Text) },
            text = { Text("应用切到后台或锁屏时会自动停止推流，避免观众看到冻结画面。需要继续请重新开播。", color = Studio.TextDim) },
            confirmButton = { TextButton({ ui.backgroundStopped = false }) { Text("知道了", color = Studio.Accent) } },
        )
    }
}

@Suppress("unused")
private fun Modifier.dim(enabled: Boolean) = this.alpha(if (enabled) 1f else 0.4f)
