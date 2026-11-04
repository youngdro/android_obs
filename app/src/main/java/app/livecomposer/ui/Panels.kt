package app.livecomposer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlignHorizontalCenter
import androidx.compose.material.icons.rounded.AlignHorizontalLeft
import androidx.compose.material.icons.rounded.AlignHorizontalRight
import androidx.compose.material.icons.rounded.AlignVerticalBottom
import androidx.compose.material.icons.rounded.AlignVerticalCenter
import androidx.compose.material.icons.rounded.AlignVerticalTop
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.livecomposer.core.Layer
import app.livecomposer.core.LayerType
import app.livecomposer.engine.EngineState
import app.livecomposer.source.PlayerSource
import app.livecomposer.source.WebOverlaySource

/** 侧边/底部面板外壳。 */
@Composable
fun PanelShell(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(Studio.Glass).border(1.dp, Studio.Outline, RoundedCornerShape(20.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            action?.invoke()
            SmallIconButton(Icons.Rounded.Close, "关闭", onClose, tint = Studio.Text)
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 14.dp), content = content)
    }
}

fun layerIcon(t: LayerType): ImageVector = when (t) {
    LayerType.CAMERA -> Icons.Rounded.Videocam
    LayerType.MEDIA -> Icons.Rounded.LiveTv
    LayerType.WEB -> Icons.Rounded.Language
    LayerType.IMAGE -> Icons.Rounded.Image
    LayerType.TEXT -> Icons.Rounded.TextFields
}

fun layerColor(t: LayerType): Color = when (t) {
    LayerType.CAMERA -> Studio.Camera
    LayerType.MEDIA -> Studio.Media
    LayerType.WEB -> Studio.Web
    LayerType.IMAGE -> Studio.Image
    LayerType.TEXT -> Studio.TextLayer
}

@Composable
private fun TypeBadge(t: LayerType, size: Int = 36) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(layerColor(t).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(layerIcon(t), null, tint = layerColor(t), modifier = Modifier.size((size * 0.55f).dp))
    }
}

// ====================================================================== 图层列表

@Composable
fun LayersPanel(
    state: EngineState,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onUpdate: (Layer) -> Unit,
    onInspect: (String) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelShell("图层", onClose, modifier, action = {
        SmallIconButton(Icons.Rounded.Add, "添加来源", onAdd, tint = Studio.Accent)
    }) {
        Text("列表顶部为画面最上层 · 长按画面中的元素也可选中", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp))
        val layers = state.layers.asReversed()
        if (layers.isEmpty()) {
            Text("还没有图层，点右上角 + 添加网页、图片或文字。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 24.dp))
        }
        layers.forEach { l ->
            val sel = l.id == selectedId
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (sel) Studio.AccentDim else Studio.Surface)
                    .border(1.dp, if (sel) Studio.Accent.copy(alpha = 0.6f) else Studio.Outline, RoundedCornerShape(14.dp))
                    .clickable { onSelect(if (sel) null else l.id) }
                    .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TypeBadge(l.type)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (l.visible) Studio.Text else Studio.TextFaint)
                    Text(layerSubtitle(l, state), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SmallIconButton(if (l.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, "显示/隐藏",
                    { onUpdate(l.copy(visible = !l.visible)) }, tint = if (l.visible) Studio.TextDim else Studio.TextFaint)
                SmallIconButton(if (l.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, "锁定",
                    { onUpdate(l.copy(locked = !l.locked)) }, tint = if (l.locked) Studio.Warn else Studio.TextFaint)
                SmallIconButton(Icons.Rounded.Tune, "属性", { onInspect(l.id) }, tint = Studio.Accent)
            }
        }
    }
}

private fun layerSubtitle(l: Layer, s: EngineState): String = when (l.type) {
    LayerType.CAMERA -> "后置主摄 · 麦克风 ${if (s.muted) "已静音" else "${(l.volume * 100).toInt()}%"}"
    LayerType.MEDIA -> buildString {
        append(s.media[l.id]?.state?.label ?: "未开始")
        append(" · 音量 ${(l.volume * 100).toInt()}% · ")
        append(if (l.mediaLive) l.mediaUri.substringAfter("://") else "本地视频")
    }
    LayerType.WEB -> buildString {
        append(s.webState.label)
        append(" · 视口 ${l.viewportW}×${l.viewportH}")
        if (l.url.isNotBlank()) append(" · ").append(l.url.removePrefix("https://").removePrefix("http://"))
    }
    LayerType.IMAGE -> "${l.w.toInt()}×${l.h.toInt()} · 不透明度 ${(l.opacity * 100).toInt()}%"
    LayerType.TEXT -> "“${l.text.replace('\n', ' ')}” · ${l.textSizePx.toInt()}px"
}

// ====================================================================== 属性检查器

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InspectorPanel(
    state: EngineState,
    layer: Layer,
    aspectLock: Boolean,
    onAspectLock: (Boolean) -> Unit,
    onUpdate: (Layer) -> Unit,
    onFitText: (String) -> Unit,
    onWeb: (url: String, vw: Int, vh: Int) -> Unit,
    onReloadWeb: () -> Unit,
    onMedia: (id: String, uri: String, loop: Boolean) -> Unit,
    onReloadMedia: (String) -> Unit,
    onRaise: () -> Unit,
    onLower: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cw = state.canvasWidth.toFloat(); val ch = state.canvasHeight.toFloat()
    PanelShell(layer.name, onClose, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            TypeBadge(layer.type, 28)
            Spacer(Modifier.width(8.dp))
            Text(layer.type.label + "图层", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            SmallIconButton(Icons.Rounded.ArrowUpward, "上移一层", onRaise)
            SmallIconButton(Icons.Rounded.ArrowDownward, "下移一层", onLower)
            SmallIconButton(if (layer.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, "显示/隐藏", { onUpdate(layer.copy(visible = !layer.visible)) })
            SmallIconButton(if (layer.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, "锁定", { onUpdate(layer.copy(locked = !layer.locked)) },
                tint = if (layer.locked) Studio.Warn else Studio.TextDim)
            if (layer.type != LayerType.CAMERA) SmallIconButton(Icons.Rounded.Delete, "删除", onDelete, tint = Studio.Live)
        }

        if (layer.type == LayerType.MEDIA) MediaSection(state, layer, onMedia, onReloadMedia)
        if (layer.type.hasAudio) AudioSection(state, layer, onUpdate)
        if (layer.type == LayerType.WEB) WebSection(state, layer, onWeb, onReloadWeb)
        if (layer.type == LayerType.TEXT) TextSection(layer, onUpdate, onFitText)

        Section("位置与尺寸（画布像素）") {
            val en = !layer.locked
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("X", layer.x, { onUpdate(layer.copy(x = it)) }, Modifier.weight(1f), en)
                NumberField("Y", layer.y, { onUpdate(layer.copy(y = it)) }, Modifier.weight(1f), en)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("宽", layer.w, { w ->
                    val nw = w.coerceAtLeast(8f)
                    onUpdate(if (aspectLock) layer.copy(w = nw, h = layer.h * nw / layer.w) else layer.copy(w = nw))
                }, Modifier.weight(1f), en)
                NumberField("高", layer.h, { h ->
                    val nh = h.coerceAtLeast(8f)
                    onUpdate(if (aspectLock) layer.copy(h = nh, w = layer.w * nh / layer.h) else layer.copy(h = nh))
                }, Modifier.weight(1f), en)
            }
            SwitchRow("锁定宽高比", "拖动角手柄时等比缩放", aspectLock, onAspectLock)
            Text("对齐", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallIconButton(Icons.Rounded.AlignHorizontalLeft, "左对齐", { onUpdate(layer.copy(x = 0f)) }, enabled = en)
                SmallIconButton(Icons.Rounded.AlignHorizontalCenter, "水平居中", { onUpdate(layer.copy(x = (cw - layer.w) / 2)) }, enabled = en)
                SmallIconButton(Icons.Rounded.AlignHorizontalRight, "右对齐", { onUpdate(layer.copy(x = cw - layer.w)) }, enabled = en)
                SmallIconButton(Icons.Rounded.AlignVerticalTop, "顶部对齐", { onUpdate(layer.copy(y = 0f)) }, enabled = en)
                SmallIconButton(Icons.Rounded.AlignVerticalCenter, "垂直居中", { onUpdate(layer.copy(y = (ch - layer.h) / 2)) }, enabled = en)
                SmallIconButton(Icons.Rounded.AlignVerticalBottom, "底部对齐", { onUpdate(layer.copy(y = ch - layer.h)) }, enabled = en)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("铺满画布", false, { onUpdate(layer.copy(x = 0f, y = 0f, w = cw, h = ch)) }, enabled = en)
                Chip("等比适应", false, {
                    val k = minOf(cw / layer.w, ch / layer.h)
                    val nw = layer.w * k; val nh = layer.h * k
                    onUpdate(layer.copy(x = (cw - nw) / 2, y = (ch - nh) / 2, w = nw, h = nh))
                }, enabled = en)
                Chip("50%", false, {
                    val nw = layer.w / 2; val nh = layer.h / 2
                    onUpdate(layer.copy(x = layer.x + nw / 2, y = layer.y + nh / 2, w = nw, h = nh))
                }, enabled = en)
                if (layer.type == LayerType.WEB) Chip("1:1 视口", false, {
                    onUpdate(layer.copy(w = layer.viewportW.toFloat(), h = layer.viewportH.toFloat()))
                }, enabled = en)
            }
        }

        Section("外观") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("不透明度", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
                var op by remember(layer.id) { mutableStateOf(layer.opacity) }
                Slider(op, { op = it; onUpdate(layer.copy(opacity = it)) }, Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Studio.Accent, activeTrackColor = Studio.Accent))
                Text("${(op * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
            }
            if (layer.type == LayerType.CAMERA) {
                Text("摄像头画面等比铺满图层框，超出部分裁掉；拖动角手柄可做画中画。", style = MaterialTheme.typography.bodySmall)
            }
            if (layer.type == LayerType.MEDIA) {
                Text("视频画面等比放进图层框，空出的部分透明。", style = MaterialTheme.typography.bodySmall)
            }
            if (layer.type == LayerType.WEB) {
                Text("透明背景由网页自身决定；默认 100%，不要用降低整体不透明度来模拟透明。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebSection(state: EngineState, layer: Layer, onWeb: (String, Int, Int) -> Unit, onReload: () -> Unit) {
    Section("网页", trailing = {
        Text("刷新", color = Studio.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onReload() }.padding(horizontal = 8.dp, vertical = 2.dp))
    }) {
        val (color, text) = when (state.webState) {
            WebOverlaySource.LoadState.READY -> Studio.Ok to "已加载${state.webViewport?.let { " · 实测视口 $it" } ?: ""}"
            WebOverlaySource.LoadState.LOADING -> Studio.Warn to "加载中（直播画面保持上一帧）"
            WebOverlaySource.LoadState.ERROR, WebOverlaySource.LoadState.CRASHED -> Studio.Live to (state.webMessage ?: state.webState.label)
            else -> Studio.TextDim to "未加载"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        }
        Field("网页地址", layer.url, { onWeb(it, layer.viewportW, layer.viewportH) }, placeholder = "https://… 透明背景网页")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("内置测试页", layer.url.startsWith("file:///android_asset/testpage"), {
                onWeb("file:///android_asset/testpage/index.html", layer.viewportW, layer.viewportH)
            })
        }
        Text("渲染视口（网页布局尺寸 CSS px，与画布中的显示大小无关）", style = MaterialTheme.typography.labelSmall)
        val presets = listOf(1920 to 1080, 1280 to 720, 1080 to 1920, 720 to 1280)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (w, h) ->
                Chip("$w×$h", layer.viewportW == w && layer.viewportH == h, { onWeb(layer.url, w, h) })
            }
            Chip("同画布", layer.viewportW == state.canvasWidth && layer.viewportH == state.canvasHeight,
                { onWeb(layer.url, state.canvasWidth, state.canvasHeight) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("视口宽", layer.viewportW.toString(), { v -> v.toIntOrNull()?.let { onWeb(layer.url, it.coerceIn(64, 3840), layer.viewportH) } },
                Modifier.weight(1f), number = true, suffix = "px")
            Field("视口高", layer.viewportH.toString(), { v -> v.toIntOrNull()?.let { onWeb(layer.url, layer.viewportW, it.coerceIn(64, 3840)) } },
                Modifier.weight(1f), number = true, suffix = "px")
        }
        Text("需要点击网页里的按钮时，使用右侧/底部的“交互”模式。", style = MaterialTheme.typography.bodySmall)
    }
}

/** 声音：音量（0~200%）与实时电平。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioSection(state: EngineState, layer: Layer, onUpdate: (Layer) -> Unit) {
    Section("声音") {
        val level = state.audioLevels[layer.id] ?: 0f
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (layer.volume <= 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, null,
                tint = if (layer.volume <= 0f) Studio.Live else Studio.TextDim, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            var vol by remember(layer.id) { mutableStateOf(layer.volume) }
            Slider(vol, { vol = (it * 20).toInt() / 20f }, Modifier.weight(1f), valueRange = 0f..2f,
                onValueChangeFinished = { onUpdate(layer.copy(volume = vol)) },
                colors = SliderDefaults.colors(thumbColor = Studio.Accent, activeTrackColor = Studio.Accent))
            Text("${(vol * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp).padding(start = 6.dp))
        }
        // 电平条
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Studio.SurfaceHigher)) {
            Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(if (level > 0.9f) Studio.Live else Studio.Ok))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0f to "静音", 0.5f to "50%", 1f to "100%", 1.5f to "150%").forEach { (v, label) ->
                Chip(label, layer.volume == v, { onUpdate(layer.copy(volume = v)) })
            }
        }
        Text(
            if (layer.type == LayerType.CAMERA) "摄像头图层的声音来自麦克风；工具栏的麦克风按钮是一键静音。"
            else "声音只混进直播，不从手机扬声器外放（避免被麦克风再次收音形成回声）。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 拉流 / 本地视频。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaSection(state: EngineState, layer: Layer, onMedia: (String, String, Boolean) -> Unit, onReload: (String) -> Unit) {
    Section(if (layer.mediaLive) "拉流" else "本地视频", trailing = {
        Text("重新连接", color = Studio.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onReload(layer.id) }.padding(horizontal = 8.dp, vertical = 2.dp))
    }) {
        val ms = state.media[layer.id]
        val color = when (ms?.state) {
            PlayerSource.State.PLAYING -> Studio.Ok
            PlayerSource.State.ERROR -> Studio.Live
            null, PlayerSource.State.IDLE, PlayerSource.State.ENDED -> Studio.TextDim
            else -> Studio.Warn
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(buildString {
                append(ms?.state?.label ?: "未开始")
                if (ms != null && ms.videoW > 0) append(" · ${ms.videoW}×${ms.videoH}")
                ms?.message?.let { append(" · ").append(it) }
            }, style = MaterialTheme.typography.bodySmall, color = color)
        }
        if (layer.mediaLive) {
            Field("拉流地址", layer.mediaUri, { onMedia(layer.id, it, layer.loop) }, placeholder = "https://….m3u8 / rtmp:// / rtsp:// / http://….flv")
            Text("支持 HLS（.m3u8）、RTMP、RTSP、HTTP-FLV、HTTP MP4。断线后按 1、2、5、10 秒自动重连。", style = MaterialTheme.typography.bodySmall)
        } else {
            SwitchRow("循环播放", "播完自动从头开始", layer.loop, { onMedia(layer.id, layer.mediaUri, it) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextSection(layer: Layer, onUpdate: (Layer) -> Unit, onFit: (String) -> Unit) {
    Section("文字") {
        Field("内容", layer.text, { onUpdate(layer.copy(text = it)); onFit(layer.id) }, singleLine = false, placeholder = "输入文字")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字号", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
            var size by remember(layer.id) { mutableStateOf(layer.textSizePx) }
            Slider(size, { size = it }, Modifier.weight(1f), valueRange = 12f..300f,
                onValueChangeFinished = { onUpdate(layer.copy(textSizePx = size)); onFit(layer.id) },
                colors = SliderDefaults.colors(thumbColor = Studio.Accent, activeTrackColor = Studio.Accent))
            Text("${size.toInt()}px", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
        }
        Text("颜色", style = MaterialTheme.typography.labelSmall)
        val swatches = listOf(0xFFFFFFFF, 0xFF111111, 0xFFFF3355, 0xFFFFB020, 0xFFFFD60A, 0xFF34D399, 0xFF3DDCFF, 0xFF5B8CFF, 0xFFB388FF, 0xFFFF7AC6)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            swatches.forEach { c ->
                val argb = c.toInt()
                val sel = layer.textColor == argb
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(Color(argb))
                        .border(if (sel) 3.dp else 1.dp, if (sel) Studio.Accent else Studio.Outline, CircleShape)
                        .clickable { onUpdate(layer.copy(textColor = argb)) },
                )
            }
        }
    }
}

// ====================================================================== 添加来源

@Composable
fun AddPanel(
    state: EngineState,
    onAddStream: (String) -> Unit,
    onPickVideo: () -> Unit,
    onAddWeb: (String) -> Unit,
    onPickImage: () -> Unit,
    onAddText: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("直播合成 LiveComposer") }
    val hasWeb = state.layers.any { it.type == LayerType.WEB }
    var pull by remember { mutableStateOf("") }
    val mediaCount = state.layers.count { it.type == LayerType.MEDIA }
    val mediaFull = mediaCount >= MAX_MEDIA_LAYERS
    PanelShell("添加来源", onClose, modifier) {
        SourceCard(LayerType.MEDIA, "拉流地址", if (mediaFull) "最多 $MAX_MEDIA_LAYERS 路媒体，已满" else "HLS / RTMP / RTSP / HTTP-FLV / MP4，画面和声音一起合进直播") {
            if (!mediaFull) {
                Field("拉流地址", pull, { pull = it }, placeholder = "https://…/live.m3u8", onChange = { pull = it })
                Chip("添加", pull.isNotBlank(), { if (pull.isNotBlank()) onAddStream(pull) }, enabled = pull.isNotBlank())
            }
        }
        SourceCard(LayerType.MEDIA, "本地视频", if (mediaFull) "最多 $MAX_MEDIA_LAYERS 路媒体，已满" else "手机里的视频文件，默认循环播放") {
            if (!mediaFull) Chip("从相册 / 文件选择", false, onPickVideo)
        }
        SourceCard(LayerType.WEB, "网页", if (hasWeb) "首版支持一个网页图层，已存在" else "透明背景网页：文字、图片、动画、实时数据") {
            if (!hasWeb) {
                Field("网页地址", url, { url = it }, placeholder = "https://…", onChange = { url = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("添加", url.isNotBlank(), { if (url.isNotBlank()) onAddWeb(url) }, enabled = url.isNotBlank())
                    Chip("使用内置测试页", false, { onAddWeb("file:///android_asset/testpage/index.html") })
                }
            }
        }
        SourceCard(LayerType.IMAGE, "图片", "本地图片，支持透明 PNG") {
            Chip("从相册 / 文件选择", false, onPickImage)
        }
        SourceCard(LayerType.TEXT, "文字", "标题、字幕、提示语") {
            Field("内容", text, { text = it }, onChange = { text = it })
            Chip("添加", text.isNotBlank(), { if (text.isNotBlank()) onAddText(text) }, enabled = text.isNotBlank())
        }
    }
}

@Composable
private fun SourceCard(type: LayerType, title: String, desc: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(Studio.Surface)
            .border(1.dp, Studio.Outline, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeBadge(type, 40)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall)
            }
        }
        content()
    }
}

/** 与 StudioEngine.MAX_MEDIA 保持一致。 */
const val MAX_MEDIA_LAYERS = 3

@Composable
fun EmptyFill() { Spacer(Modifier.fillMaxSize()) }

@Composable
fun VSpace(h: Int) { Spacer(Modifier.height(h.dp)) }
