package app.livecomposer.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.livecomposer.core.Layer
import app.livecomposer.core.LayerType
import app.livecomposer.source.PlayerSource
import app.livecomposer.engine.EngineState
import app.livecomposer.source.WebOverlaySource
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Handle(val left: Boolean, val right: Boolean, val top: Boolean, val bottom: Boolean) {
    TL(true, false, true, false), T(false, false, true, false), TR(false, true, true, false), R(false, true, false, false),
    BR(false, true, false, true), B(false, false, false, true), BL(true, false, false, true), L(true, false, false, false);

    val corner get() = (left || right) && (top || bottom)
}

/** 图层操作回调。 */
class StageActions(
    val onSurface: (Surface?, Int, Int) -> Unit,
    val onSelect: (String?) -> Unit,
    val onLayerChange: (Layer, Boolean) -> Unit,
    val onFocus: (Float, Float) -> Unit,
    val onZoom: (Float) -> Unit,
    val onWebTouch: (MotionEvent, Float, Float) -> Boolean,
    val onInspect: () -> Unit,
    val onRaise: (String) -> Unit,
    val onLower: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onReloadWeb: () -> Unit,
    val onReloadMedia: (String) -> Unit,
)

/**
 * 合成预览舞台：SurfaceView 显示合成器输出（与编码器同一张 FBO），上方是编辑层。
 * 编辑层的选中框、手柄、辅助线、浮动工具栏都只存在于界面，不会进入输出视频。
 */
@OptIn(ExperimentalComposeUiApi::class)
@SuppressLint("ClickableViewAccessibility")
@Composable
fun Stage(
    state: EngineState,
    selectedId: String?,
    interaction: Boolean,
    aspectLock: Boolean,
    actions: StageActions,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val st by rememberUpdatedState(state)
    val sel by rememberUpdatedState(selectedId)
    val lockAspect by rememberUpdatedState(aspectLock)
    val act by rememberUpdatedState(actions)

    var guidesX by remember { mutableStateOf(emptyList<Float>()) }
    var guidesY by remember { mutableStateOf(emptyList<Float>()) }
    var dragging by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var zoomTick by remember { mutableStateOf(0) }
    var zoomVisible by remember { mutableStateOf(false) }
    LaunchedEffect(zoomTick) {
        if (zoomTick > 0) { zoomVisible = true; kotlinx.coroutines.delay(1500); zoomVisible = false }
    }
    val focusAnim = remember { Animatable(0f) }

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            focusAnim.snapTo(0f)
            focusAnim.animateTo(1f, tween(900))
            focusPoint = null
        }
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = state.canvasWidth.toFloat() / state.canvasHeight
        val maxWpx = constraints.maxWidth.toFloat()
        val maxHpx = constraints.maxHeight.toFloat()
        val fitW = minOf(maxWpx, maxHpx * ratio)
        val fitH = fitW / ratio
        val wDp = with(density) { fitW.toDp() }
        val hDp = with(density) { fitH.toDp() }
        val toView = fitW / state.canvasWidth // 画布像素 → 视图像素

        Box(Modifier.size(wDp, hDp).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {}
                            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) = act.onSurface(h.surface, w, hh)
                            override fun surfaceDestroyed(h: SurfaceHolder) = act.onSurface(null, 0, 0)
                        })
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // ---------------- 手势层 ----------------
            val gestureModifier = if (interaction) {
                Modifier.pointerInteropFilter { ev ->
                    val s = st.canvasWidth / fitW
                    act.onWebTouch(ev, ev.x * s, ev.y * s)
                }
            } else {
                Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val t0 = System.currentTimeMillis()
                        val s = st.canvasWidth / size.width.toFloat()
                        val p0 = Offset(down.position.x * s, down.position.y * s)
                        val tol = 22.dp.toPx() * s
                        val layers = st.layers
                        val selected = layers.firstOrNull { it.id == sel }
                        var target: Layer? = selected?.takeIf { !it.locked && it.visible }
                        var handle: Handle? = target?.let { hitHandle(it, p0, tol) }
                        var mode = when {
                            handle != null -> 2
                            target != null && inside(target, p0, tol / 2) -> 1
                            else -> 0
                        }
                        if (mode == 0) target = null
                        var start = target?.copy()
                        var current: Layer? = null
                        var pan = Offset.Zero
                        var moved = false
                        var multi = false
                        var longFired = false
                        var pinch = 1f
                        val slop = viewConfiguration.touchSlop
                        val longMs = viewConfiguration.longPressTimeoutMillis
                        while (true) {
                            val remaining = longMs - (System.currentTimeMillis() - t0)
                            val ev = if (!moved && !multi && !longFired && mode == 0 && remaining > 0) {
                                withTimeoutOrNull(remaining) { awaitPointerEvent() }
                            } else awaitPointerEvent()
                            if (ev == null) {
                                // 长按：选中手指下最上层的可见图层，并可直接继续拖动
                                val hit = layers.lastOrNull { it.visible && inside(it, p0, 0f) }
                                longFired = true
                                if (hit != null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    act.onSelect(hit.id)
                                    if (!hit.locked) { target = hit; start = hit.copy(); mode = 1; pan = Offset.Zero }
                                }
                                continue
                            }
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                multi = true
                                val z = ev.calculateZoom()
                                if (z != 1f) {
                                    val base = start ?: selected?.takeIf { !it.locked && it.visible }?.copy()?.also { start = it }
                                    if (base != null) {
                                        pinch = (pinch * z).coerceIn(0.05f, 20f)
                                        val nw = (base.w * pinch).coerceAtLeast(24f)
                                        val nh = (base.h * pinch).coerceAtLeast(24f)
                                        val cx = base.x + base.w / 2; val cy = base.y + base.h / 2
                                        val nl = base.copy(x = cx - nw / 2, y = cy - nh / 2, w = nw, h = nh)
                                        current = nl; dragging = true
                                        act.onLayerChange(nl, false)
                                    } else {
                                        act.onZoom(z); zoomTick++
                                    }
                                }
                                ev.changes.forEach { it.consume() }
                                continue
                            }
                            val c = pressed.first()
                            pan += c.positionChange()
                            if (!moved && pan.getDistance() > slop) moved = true
                            val base = start
                            if (moved && !multi && base != null && mode != 0) {
                                val d = Offset(pan.x * s, pan.y * s)
                                val nl = if (mode == 1) {
                                    val (l, gx, gy) = snapMove(base, d, st.canvasWidth.toFloat(), st.canvasHeight.toFloat(), 10.dp.toPx() * s)
                                    guidesX = gx; guidesY = gy; l
                                } else resize(base, handle!!, d, lockAspect)
                                current = nl; dragging = true
                                act.onLayerChange(nl, false)
                                c.consume()
                            }
                        }
                        guidesX = emptyList(); guidesY = emptyList(); dragging = false
                        current?.let { act.onLayerChange(it, true) }
                        if (!moved && !multi && !longFired) {
                            // 单击：未选中 → 对焦；已选中 → 点其他图层切换，点自身或空白取消选择
                            // （摄像头图层通常铺满画面，单击不切换到它，避免“点空白”变成选中摄像头；长按或列表可选中）
                            val hit = layers.lastOrNull { it.visible && it.type != LayerType.CAMERA && inside(it, p0, 0f) }
                            if (selected == null) {
                                act.onFocus(p0.x, p0.y)
                                focusPoint = Offset(p0.x / s, p0.y / s)
                            } else if (hit != null && hit.id != selected.id) {
                                act.onSelect(hit.id)
                            } else {
                                act.onSelect(null)
                            }
                        }
                    }
                }
            }

            Canvas(Modifier.fillMaxSize().then(gestureModifier)) {
                val sc = toView
                // 所有图层的淡轮廓（便于发现可编辑元素）
                if (!interaction) {
                    for (l in state.layers) {
                        if (!l.visible || l.id == selectedId) continue
                        drawRect(Color.White.copy(alpha = 0.22f), Offset(l.x * sc, l.y * sc), Size(l.w * sc, l.h * sc),
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
                    }
                }
                val s = state.layers.firstOrNull { it.id == selectedId }
                if (s != null && !interaction) {
                    val tl = Offset(s.x * sc, s.y * sc)
                    val sz = Size(s.w * sc, s.h * sc)
                    drawRect(Color.Black.copy(alpha = 0.35f), tl, sz, style = Stroke(4.dp.toPx()))
                    drawRect(if (s.locked) Studio.TextDim else Studio.Accent, tl, sz, style = Stroke(2.dp.toPx()))
                    if (!s.locked) {
                        val r = 6.dp.toPx()
                        for (h in Handle.values()) {
                            val p = handlePos(s, h)
                            val c = Offset(p.x * sc, p.y * sc)
                            if (h.corner) {
                                drawCircle(Color.White, r, c)
                                drawCircle(Studio.Accent, r, c, style = Stroke(2.dp.toPx()))
                            } else {
                                val horizontal = h == Handle.T || h == Handle.B
                                val hw = if (horizontal) 14.dp.toPx() else 5.dp.toPx()
                                val hh = if (horizontal) 5.dp.toPx() else 14.dp.toPx()
                                drawRoundRect(Color.White, Offset(c.x - hw / 2, c.y - hh / 2), Size(hw, hh), CornerRadius(3.dp.toPx()))
                            }
                        }
                    }
                }
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
                for (gx in guidesX) drawLine(Studio.Guide, Offset(gx * sc, 0f), Offset(gx * sc, size.height), 1.5.dp.toPx(), pathEffect = dash)
                for (gy in guidesY) drawLine(Studio.Guide, Offset(0f, gy * sc), Offset(size.width, gy * sc), 1.5.dp.toPx(), pathEffect = dash)
                focusPoint?.let { fp ->
                    val k = focusAnim.value
                    val rr = (44.dp.toPx() * (1.3f - 0.3f * k))
                    drawRoundRect(Studio.Guide.copy(alpha = 1f - k * 0.6f), Offset(fp.x - rr, fp.y - rr), Size(rr * 2, rr * 2),
                        CornerRadius(10.dp.toPx()), style = Stroke(2.dp.toPx()))
                }
            }

            // ---------------- 选中尺寸标签 / 浮动工具栏 ----------------
            val s = state.layers.firstOrNull { it.id == selectedId }
            if (s != null && !interaction) {
                val leftPx = (s.x * toView).coerceIn(0f, fitW)
                val topPx = s.y * toView
                val bottomPx = (s.y + s.h) * toView
                if (dragging) {
                    Text("${s.w.roundToInt()} × ${s.h.roundToInt()}  ·  ${s.x.roundToInt()}, ${s.y.roundToInt()}",
                        color = Color.Black, fontSize = 11.sp,
                        modifier = Modifier.offset { IntOffset(leftPx.roundToInt(), (bottomPx + 6.dp.toPx()).coerceAtMost(fitH - 24.dp.toPx()).roundToInt()) }
                            .clip(RoundedCornerShape(6.dp)).background(Studio.Accent).padding(horizontal = 6.dp, vertical = 2.dp))
                } else {
                    val barW = with(density) { 230.dp.toPx() }
                    val barH = with(density) { 44.dp.toPx() }
                    val x = leftPx.coerceIn(0f, (fitW - barW).coerceAtLeast(0f))
                    val y = if (topPx - barH - 8 > 0) topPx - barH - 8 else (bottomPx + 8).coerceAtMost(fitH - barH)
                    Row(
                        Modifier.offset { IntOffset(x.roundToInt(), y.coerceAtLeast(0f).roundToInt()) }
                            .clip(RoundedCornerShape(12.dp)).background(Studio.Glass).border(1.dp, Studio.Outline, RoundedCornerShape(12.dp))
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SmallIconButton(Icons.Rounded.Tune, "属性", { actions.onInspect() }, tint = Studio.Accent)
                        SmallIconButton(Icons.Rounded.ArrowUpward, "上移一层", { actions.onRaise(s.id) })
                        SmallIconButton(Icons.Rounded.ArrowDownward, "下移一层", { actions.onLower(s.id) })
                        SmallIconButton(Icons.Rounded.VisibilityOff, "隐藏", { actions.onLayerChange(s.copy(visible = false), true); actions.onSelect(null) })
                        SmallIconButton(if (s.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, "锁定",
                            { actions.onLayerChange(s.copy(locked = !s.locked), true) }, tint = if (s.locked) Studio.Warn else Studio.TextDim)
                        if (s.type != LayerType.CAMERA) SmallIconButton(Icons.Rounded.Delete, "删除", { actions.onDelete(s.id) }, tint = Studio.Live)
                    }
                }
            }

            // ---------------- 网页状态角标（仅界面） ----------------
            state.layers.firstOrNull { it.type == LayerType.WEB && it.visible }?.let { w ->
                val badge: Pair<String, Color>? = when (state.webState) {
                    WebOverlaySource.LoadState.LOADING -> "网页加载中…（直播画面保持上一帧）" to Studio.Warn
                    WebOverlaySource.LoadState.ERROR -> "网页加载失败 · 点击刷新" to Studio.Live
                    WebOverlaySource.LoadState.CRASHED -> "网页进程已崩溃 · 点击恢复" to Studio.Live
                    else -> null
                }
                if (badge != null) {
                    val bx = (w.x * toView).coerceIn(0f, fitW * 0.5f) + with(density) { 8.dp.toPx() }
                    val by = (w.y * toView).coerceIn(0f, fitH * 0.7f) + with(density) { 8.dp.toPx() }
                    Text(badge.first, color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.offset { IntOffset(bx.roundToInt(), by.roundToInt()) }.widthIn(max = 260.dp)
                            .clip(RoundedCornerShape(8.dp)).background(badge.second.copy(alpha = 0.9f))
                            .clickable(enabled = state.webState != WebOverlaySource.LoadState.LOADING) { actions.onReloadWeb() }
                            .padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }

            // ---------------- 媒体图层状态角标（仅界面） ----------------
            state.layers.filter { it.type == LayerType.MEDIA && it.visible }.forEach { m ->
                val ms = state.media[m.id]
                val badge: Pair<String, Color>? = when (ms?.state) {
                    null -> null
                    PlayerSource.State.CONNECTING -> "${m.name} 连接中…" to Studio.Warn
                    PlayerSource.State.BUFFERING -> "${m.name} 缓冲中…" to Studio.Warn
                    PlayerSource.State.RECONNECTING -> "${m.name} ${ms?.message ?: "重连中"}" to Studio.Warn
                    PlayerSource.State.ERROR -> "${m.name} ${ms?.message ?: "播放失败"} · 点击重试" to Studio.Live
                    PlayerSource.State.ENDED -> "${m.name} 已播完 · 点击重播" to Studio.TextDim
                    else -> null
                }
                if (badge != null) {
                    val bx = (m.x * toView).coerceIn(0f, fitW * 0.6f) + with(density) { 8.dp.toPx() }
                    val by = (m.y * toView).coerceIn(0f, fitH * 0.8f) + with(density) { 8.dp.toPx() }
                    Text(badge.first, color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.offset { IntOffset(bx.roundToInt(), by.roundToInt()) }.widthIn(max = 260.dp)
                            .clip(RoundedCornerShape(8.dp)).background(badge.second.copy(alpha = 0.9f))
                            .clickable { actions.onReloadMedia(m.id) }
                            .padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }

            // ---------------- 变焦提示 ----------------
            AnimatedVisibility(zoomVisible, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), enter = fadeIn(), exit = fadeOut()) {
                GlassChip("%.1f×".format(state.zoom), Studio.Guide)
            }
        }
    }
}

private fun inside(l: Layer, p: Offset, pad: Float) =
    p.x >= l.x - pad && p.x <= l.x + l.w + pad && p.y >= l.y - pad && p.y <= l.y + l.h + pad

private fun handlePos(l: Layer, h: Handle): Offset {
    val x = when { h.left -> l.x; h.right -> l.x + l.w; else -> l.x + l.w / 2 }
    val y = when { h.top -> l.y; h.bottom -> l.y + l.h; else -> l.y + l.h / 2 }
    return Offset(x, y)
}

private fun hitHandle(l: Layer, p: Offset, tol: Float): Handle? =
    Handle.values().sortedBy { if (it.corner) 0 else 1 }.firstOrNull { h ->
        val c = handlePos(l, h)
        abs(c.x - p.x) <= tol && abs(c.y - p.y) <= tol
    }

/** 移动并吸附到画布边缘 / 中线。返回 (新图层, 竖向辅助线 x, 横向辅助线 y)。 */
private fun snapMove(base: Layer, d: Offset, cw: Float, ch: Float, thr: Float): Triple<Layer, List<Float>, List<Float>> {
    var nx = base.x + d.x
    var ny = base.y + d.y
    val gx = ArrayList<Float>(); val gy = ArrayList<Float>()
    var best = thr
    var snapX: Float? = null
    for (target in floatArrayOf(0f, cw / 2, cw)) for ((edge, off) in listOf(nx to 0f, nx + base.w / 2 to base.w / 2, nx + base.w to base.w)) {
        val diff = abs(edge - target)
        if (diff < best) { best = diff; snapX = target - off; gx.clear(); gx.add(target) }
    }
    snapX?.let { nx = it }
    best = thr
    var snapY: Float? = null
    for (target in floatArrayOf(0f, ch / 2, ch)) for ((edge, off) in listOf(ny to 0f, ny + base.h / 2 to base.h / 2, ny + base.h to base.h)) {
        val diff = abs(edge - target)
        if (diff < best) { best = diff; snapY = target - off; gy.clear(); gy.add(target) }
    }
    snapY?.let { ny = it }
    return Triple(base.copy(x = nx, y = ny), gx, gy)
}

/** 拖动手柄缩放；角手柄在锁定比例时等比缩放并固定对角。 */
private fun resize(base: Layer, h: Handle, d: Offset, lockAspect: Boolean): Layer {
    val min = 24f
    var x = base.x; var y = base.y; var w = base.w; var hh = base.h
    if (h.left) { w = (base.w - d.x).coerceAtLeast(min); x = base.x + base.w - w }
    if (h.right) w = (base.w + d.x).coerceAtLeast(min)
    if (h.top) { hh = (base.h - d.y).coerceAtLeast(min); y = base.y + base.h - hh }
    if (h.bottom) hh = (base.h + d.y).coerceAtLeast(min)
    if (h.corner && lockAspect) {
        val sw = w / base.w; val sh = hh / base.h
        val k = if (abs(sw - 1) > abs(sh - 1)) sw else sh
        w = (base.w * k).coerceAtLeast(min); hh = (base.h * k).coerceAtLeast(min)
        x = if (h.left) base.x + base.w - w else base.x
        y = if (h.top) base.y + base.h - hh else base.y
    }
    return base.copy(x = x, y = y, w = w, h = hh)
}
