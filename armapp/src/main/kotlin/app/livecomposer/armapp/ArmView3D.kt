package app.livecomposer.armapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import app.livecomposer.arm.ArmIk
import app.livecomposer.arm.ArmKinematics
import app.livecomposer.arm.Rot
import app.livecomposer.arm.ToolPose
import kotlin.math.cos
import kotlin.math.sin

/**
 * 机械臂 3D 视图（正交投影，纯 Canvas 绘制，安卓同样可用）：拖动旋转视角，滚轮缩放。
 * 实线 = 舵机实际位置，浅色 = 下发的目标（设定点），十字 = 最近一次“前往”的目标位姿。
 */
/** 正交投影：方位角绕 Z、仰角绕屏幕水平轴。屏幕上的拖动可以反算成世界坐标里的平移。 */
private class Projection(val azimuth: Double, val elevation: Double, val scale: Double, val center: Offset) {
    private val ca = cos(azimuth); private val sa = sin(azimuth); private val ce = cos(elevation); private val se = sin(elevation)
    fun proj(p: DoubleArray): Offset {
        val x1 = p[0] * ca - p[1] * sa
        val y1 = p[0] * sa + p[1] * ca
        val zScreen = p[2] * ce - y1 * se
        return Offset((center.x + x1 * scale).toFloat(), (center.y - zScreen * scale).toFloat())
    }
    /** 屏幕位移（像素）→ 与屏幕平行的世界平移（米）。 */
    fun unproject(dx: Float, dy: Float): DoubleArray {
        val r = dx / scale; val u = -dy / scale
        return doubleArrayOf(r * ca - u * se * sa, -r * sa - u * se * ca, u * ce)
    }
}

/**
 * 机械臂 3D 视图（正交投影，纯 Canvas 绘制，安卓同样可用）。
 * 拖动橙色夹爪点 = 设定目标位置（松手后机械臂过去），拖动空白处 = 旋转视角，滚轮 = 缩放。
 * 实线 = 舵机实际位置，浅色 = 下发的目标（设定点），橙色虚影 = 正在拖的目标的逆解姿态。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ArmView3D(
    ik: ArmIk,
    measured: DoubleArray?,
    setpoint: DoubleArray?,
    target: ToolPose?,
    dark: Boolean,
    modifier: Modifier = Modifier,
    /** 当前夹爪位姿（拖动的起点），为空时不能拖。 */
    tool: ToolPose? = null,
    onTargetDrop: (ToolPose) -> Unit = {},
    onPress: () -> Unit = {},
) {
    var azimuth by remember { mutableStateOf(-0.6) }
    var elevation by remember { mutableStateOf(0.45) }
    var zoom by remember { mutableStateOf(1.0) }
    var viewSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size(1f, 1f)) }
    var dragPose by remember { mutableStateOf<ToolPose?>(null) }
    var dragPreview by remember { mutableStateOf<ArmIk.Result?>(null) }
    val toolNow by androidx.compose.runtime.rememberUpdatedState(tool)
    val seedNow by androidx.compose.runtime.rememberUpdatedState(setpoint ?: measured)
    val dropNow by androidx.compose.runtime.rememberUpdatedState(onTargetDrop)
    val pressNow by androidx.compose.runtime.rememberUpdatedState(onPress)

    fun projection() = Projection(
        azimuth, elevation,
        // 机械臂伸直约 0.5 m 高：按窗口高度留出 0.65 m，底座放在下方 85% 处
        minOf(viewSize.height / 0.65, viewSize.width / 0.7) * zoom,
        Offset(viewSize.width / 2, viewSize.height * 0.85f),
    )

    Canvas(
        modifier.fillMaxSize()
            .onSizeChanged { viewSize = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressNow()
                    val pr = projection()
                    val t = toolNow
                    val onAnchor = t != null && (pr.proj(t.p) - down.position).getDistance() < 24f
                    var start = t
                    var acc = Offset.Zero
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val d = ch.position - ch.previousPosition
                        ch.consume()
                        if (onAnchor && start != null) {
                            acc += d
                            val w = pr.unproject(acc.x, acc.y)
                            val pose = start.translated(w[0], w[1], w[2])
                            dragPose = pose
                            dragPreview = seedNow?.let { ik.solve(pose, it, maxIter = 40) }
                        } else {
                            azimuth -= d.x / 200.0
                            elevation = (elevation + d.y / 200.0).coerceIn(-1.5, 1.5)
                        }
                    }
                    dragPose?.let { dropNow(it) }
                    dragPose = null; dragPreview = null
                }
            }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                zoom = (zoom * (if (dy > 0) 0.9 else 1.1)).coerceIn(0.3, 5.0)
            },
    ) {
        val pr = projection()
        fun proj(p: DoubleArray) = pr.proj(p)
        val grid = if (dark) Color(0x33FFFFFF) else Color(0x22000000)
        for (k in -6..6) {
            val v = k * 0.05
            drawLine(grid, proj(doubleArrayOf(v, -0.3, 0.0)), proj(doubleArrayOf(v, 0.3, 0.0)))
            drawLine(grid, proj(doubleArrayOf(-0.3, v, 0.0)), proj(doubleArrayOf(0.3, v, 0.0)))
        }
        val o = doubleArrayOf(0.0, 0.0, 0.0)
        drawLine(Color(0xFFE5484D), proj(o), proj(doubleArrayOf(0.08, 0.0, 0.0)), 3f)
        drawLine(Color(0xFF30A46C), proj(o), proj(doubleArrayOf(0.0, 0.08, 0.0)), 3f)
        drawLine(Color(0xFF3E63DD), proj(o), proj(doubleArrayOf(0.0, 0.0, 0.08)), 3f)

        val armColor = if (dark) Color(0xFFE8E8E8) else Color(0xFF333333)
        setpoint?.let { drawArm(ik, it, ::proj, armColor.copy(alpha = 0.25f), 10f, false) }
        measured?.let { drawArm(ik, it, ::proj, armColor, 8f, true) }
        dragPreview?.let { r ->
            val c = if (r.reached) Color(0xFFF76B15) else Color(0xFFE5484D)
            drawArm(ik, r.q, ::proj, c.copy(alpha = 0.45f), 6f, false)
        }
        dragPose?.let { p ->
            val c = if (dragPreview?.reached == true) Color(0xFFF76B15) else Color(0xFFE5484D)
            drawCircle(c, 9f, proj(p.p))
        }
        // 可拖动的夹爪锚点
        tool?.let { drawCircle(Color(0xFFF76B15).copy(alpha = 0.35f), 16f, proj(it.p)) }

        target?.let { t ->
            val c = proj(t.p); val r = 10f
            val tc = Color(0xFFF76B15)
            drawLine(tc, c + Offset(-r, 0f), c + Offset(r, 0f), 3f)
            drawLine(tc, c + Offset(0f, -r), c + Offset(0f, r), 3f)
            val x = Rot.mulV(t.r(), doubleArrayOf(0.04, 0.0, 0.0))
            drawLine(tc.copy(alpha = 0.6f), c, proj(doubleArrayOf(t.x + x[0], t.y + x[1], t.z + x[2])), 2f)
        }
    }
}

private fun DrawScope.drawArm(ik: ArmIk, q: DoubleArray, proj: (DoubleArray) -> Offset, color: Color, width: Float, axes: Boolean) {
    val pts = ArmKinematics.chain(q)
    val (tp, tr) = ik.fk(q)
    val all = pts + listOf(tp)
    for (i in 1 until all.size) drawLine(color, proj(all[i - 1]), proj(all[i]), width, cap = StrokeCap.Round)
    for (p in pts) drawCircle(color, width * 0.9f, proj(p))
    if (axes) {
        // 夹爪坐标系：橙 = 指向，绿 = Y，蓝 = Z
        val len = 0.05
        listOf(0 to Color(0xFFF76B15), 1 to Color(0xFF30A46C), 2 to Color(0xFF3E63DD)).forEach { (k, c) ->
            val a = doubleArrayOf(tr[k] * len, tr[3 + k] * len, tr[6 + k] * len)
            drawLine(c, proj(tp), proj(doubleArrayOf(tp[0] + a[0], tp[1] + a[1], tp[2] + a[2])), 3f)
        }
        drawCircle(Color(0xFFF76B15), 6f, proj(tp))
    }
}
