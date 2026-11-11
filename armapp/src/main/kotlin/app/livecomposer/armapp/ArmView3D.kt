package app.livecomposer.armapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ArmView3D(
    ik: ArmIk,
    measured: DoubleArray?,
    setpoint: DoubleArray?,
    target: ToolPose?,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    var azimuth by remember { mutableStateOf(-0.6) }
    var elevation by remember { mutableStateOf(0.45) }
    var zoom by remember { mutableStateOf(1.0) }

    Canvas(
        modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { _, d ->
                    azimuth -= d.x / 200.0
                    elevation = (elevation + d.y / 200.0).coerceIn(-1.5, 1.5)
                }
            }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                zoom = (zoom * (if (dy > 0) 0.9 else 1.1)).coerceIn(0.3, 5.0)
            },
    ) {
        val scale = size.minDimension * 1.6 * zoom
        val center = Offset(size.width / 2, size.height * 0.68f)
        fun proj(p: DoubleArray): Offset {
            val ca = cos(azimuth); val sa = sin(azimuth)
            val x1 = p[0] * ca - p[1] * sa
            val y1 = p[0] * sa + p[1] * ca
            val ce = cos(elevation); val se = sin(elevation)
            val zScreen = p[2] * ce - y1 * se
            return Offset((center.x + x1 * scale).toFloat(), (center.y - zScreen * scale).toFloat())
        }
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
