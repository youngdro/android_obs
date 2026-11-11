package app.livecomposer.armapp

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.livecomposer.arm.ArmCalibration
import app.livecomposer.arm.ArmController
import app.livecomposer.arm.ToolPose
import kotlinx.coroutines.delay
import java.io.File

fun main(args: Array<String>) {
    val calFile = args.firstOrNull { it.endsWith(".json") }?.let { File(it) }
    val cal = calFile?.takeIf { it.exists() }?.let { ArmCalibration.fromJson(org.json.JSONObject(it.readText())) } ?: ArmCalibration.A003_ARM2
    val session = ArmSession(cal, File(System.getProperty("user.home"), ".livecomposer-arm"))
    // 演示/自检：ARMAPP_DEMO=1 时自动连模拟机械臂、上力并走到一个姿态
    if (System.getenv("ARMAPP_DEMO") != null) Thread {
        session.connect(ArmSession.SIM); Thread.sleep(300); session.enable(55); Thread.sleep(500)
        session.moveToPose(session.ik.forward(doubleArrayOf(0.5, -1.0, -1.6, 0.0, 1.2, 0.3)))
    }.apply { isDaemon = true }.start()
    application {
        Window(
            onCloseRequest = { session.disconnect(); exitApplication() },
            title = "机械臂操控",
            state = rememberWindowState(width = 1280.dp, height = 820.dp),
            onPreviewKeyEvent = { e -> handleKey(session, e.key, e.type == KeyEventType.KeyDown) },
        ) {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { ArmScreen(session, dark) }
            }
        }
    }
}

/** 键盘点动：W/S 前后(X) A/D 左右(Y) R/F 上下(Z)，Q/E 水平转向，I/K 俯仰，J/L 自转，空格急停。 */
private val keyAxes = mapOf(
    Key.W to (0 to 1.0), Key.S to (0 to -1.0), Key.A to (1 to 1.0), Key.D to (1 to -1.0), Key.R to (2 to 1.0), Key.F to (2 to -1.0),
    Key.Q to (5 to 1.0), Key.E to (5 to -1.0), Key.I to (4 to -1.0), Key.K to (4 to 1.0), Key.J to (3 to -1.0), Key.L to (3 to 1.0),
)
private val heldKeys = HashSet<Key>()
var linSpeed = 0.03
var angSpeed = 0.3

private fun handleKey(s: ArmSession, key: Key, down: Boolean): Boolean {
    if (key == Key.Spacebar && down) { s.stop(); return true }
    if (key !in keyAxes) return false
    if (focusedTextField) return false
    if (down) heldKeys += key else heldKeys -= key
    if (heldKeys.isEmpty()) { s.heldToolJog = null; return true }
    val v = DoubleArray(6)
    for (k in heldKeys) keyAxes[k]?.let { (i, sgn) -> v[i] += sgn * (if (i < 3) linSpeed else angSpeed) }
    // 键盘：平移按底座坐标系；俯仰/自转按夹爪坐标系更直观，所以有转动键时整体用夹爪坐标系
    val tool = heldKeys.any { (keyAxes[it]?.first ?: 0) in 3..4 }
    s.heldToolFrame = tool
    s.heldToolJog = v
    return true
}

@Volatile var focusedTextField = false

@Composable
fun ArmScreen(s: ArmSession, dark: Boolean) {
    var snap by remember { mutableStateOf(s.snapshot) }
    LaunchedEffect(Unit) { while (true) { snap = s.snapshot; delay(50) } }
    val st = snap.state
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight().background(if (dark) Color(0xFF16181C) else Color(0xFFF4F5F7))) {
            ArmView3D(s.ik, st?.q, st?.takeIf { it.mode != ArmController.Mode.OFF }?.setpoint, snap.toolTarget, dark)
            Column(Modifier.padding(12.dp).align(Alignment.TopStart)) {
                Text(statusLine(snap), fontWeight = FontWeight.SemiBold)
                snap.message?.let { Text(it, fontSize = 13.sp) }
                if (st?.toolBlocked == true) Text("够不着：已到限位或工作空间边缘", color = Color(0xFFE5484D), fontSize = 13.sp)
                Text("拖动旋转视角，滚轮缩放", fontSize = 11.sp, color = Color.Gray)
            }
            Column(Modifier.padding(12.dp).align(Alignment.BottomStart)) {
                Text("键盘：W/S 前后  A/D 左右  R/F 上下  Q/E 转向  I/K 俯仰  J/L 自转  空格 急停", fontSize = 12.sp, color = Color.Gray)
            }
        }
        Column(Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ConnectionPanel(s, snap)
            HorizontalDivider()
            PosePanel(s, snap)
            HorizontalDivider()
            JogPanel(s)
            HorizontalDivider()
            GripperPanel(s, snap)
            HorizontalDivider()
            RecordingPanel(s, snap)
            HorizontalDivider()
            JointsPanel(s, snap)
        }
    }
}

private fun statusLine(s: ArmSession.Snapshot): String {
    val st = s.state ?: return "未连接"
    val mode = when (st.mode) {
        ArmController.Mode.OFF -> "卸力"
        ArmController.Mode.IDLE -> "保持"
        ArmController.Mode.MOVING -> "移动中"
        ArmController.Mode.JOG, ArmController.Mode.JOG_TOOL -> "点动"
        ArmController.Mode.PLAYBACK -> "回放 ${"%.0f".format((st.playbackProgress ?: 0.0) * 100)}%"
        ArmController.Mode.FAULT -> "故障：${st.fault}"
    }
    return "${s.link}  ·  $mode" + if (st.mode != ArmController.Mode.OFF) "  ·  力矩 ${s.torquePct}%" else ""
}

@Composable
private fun ConnectionPanel(s: ArmSession, snap: ArmSession.Snapshot) {
    var ports by remember { mutableStateOf(listOf(ArmSession.SIM) + SerialLink.ports()) }
    var open by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf(ports.getOrElse(1) { ports[0] }) }
    var pct by remember { mutableStateOf(55f) }
    val st = snap.state
    Text("连接", fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { ports = listOf(ArmSession.SIM) + SerialLink.ports(); open = true }, Modifier.fillMaxWidth()) {
                Text(port.substringAfterLast('/'), maxLines = 1)
            }
            DropdownMenu(open, { open = false }) {
                ports.forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { port = p; open = false }) }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (snap.link == null) Button({ s.connect(port) }) { Text("连接") } else OutlinedButton({ s.disconnect() }) { Text("断开") }
    }
    if (st != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("力矩上限 ${pct.toInt()}%", Modifier.width(110.dp), fontSize = 13.sp)
            Slider(pct, { pct = it }, valueRange = 30f..80f, steps = 9, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button({ s.enable(pct.toInt()) }) { Text(if (st.mode == ArmController.Mode.OFF) "上力" else "应用") }
            OutlinedButton({ s.home() }) { Text("回零") }
            OutlinedButton({ s.disable() }) { Text("卸力") }
            if (st.mode == ArmController.Mode.FAULT) OutlinedButton({ s.clearFault() }) { Text("清除故障") }
        }
        Button({ s.stop() }, Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5484D))) {
            Text("急停（空格）", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PosePanel(s: ArmSession, snap: ArmSession.Snapshot) {
    val t = snap.tool
    Text("夹爪位姿", fontWeight = FontWeight.Bold)
    Text(
        if (t == null) "—" else "X ${mm(t.x)}  Y ${mm(t.y)}  Z ${mm(t.z)} mm\n转向 ${deg(t.yaw)}  俯仰 ${deg(t.pitch)}  自转 ${deg(t.roll)} °",
        fontFamily = FontFamily.Monospace, fontSize = 13.sp,
    )
    val fields = remember { List(6) { mutableStateOf("") } }
    val labels = listOf("X mm", "Y mm", "Z mm", "转向°", "俯仰°", "自转°")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (k in 0..2) NumField(labels[k], fields[k].value, { fields[k].value = it }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (k in 3..5) NumField(labels[k], fields[k].value, { fields[k].value = it }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton({
            t?.let { p -> listOf(p.x * 1000, p.y * 1000, p.z * 1000, Math.toDegrees(p.yaw), Math.toDegrees(p.pitch), Math.toDegrees(p.roll)).forEachIndexed { i, v -> fields[i].value = "%.0f".format(v) } }
        }) { Text("填入当前") }
        Button({
            val v = fields.map { it.value.toDoubleOrNull() }
            if (v.all { it != null }) s.moveToPose(ToolPose(v[0]!! / 1000, v[1]!! / 1000, v[2]!! / 1000, Math.toRadians(v[3]!!), Math.toRadians(v[4]!!), Math.toRadians(v[5]!!)))
        }) { Text("前往") }
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(value, onChange, modifier.onFocusTrack(), label = { Text(label, fontSize = 11.sp) }, singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
}

/** 输入框获得焦点时，键盘点动让路给打字。 */
private fun Modifier.onFocusTrack(): Modifier = this.onFocusChanged { focusedTextField = it.isFocused }

@Composable
private fun JogPanel(s: ArmSession) {
    var lin by remember { mutableStateOf(linSpeed.toFloat() * 1000) }
    var ang by remember { mutableStateOf(Math.toDegrees(angSpeed).toFloat()) }
    var toolFrame by remember { mutableStateOf(false) }
    Text("点动（按住）", fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(toolFrame, { toolFrame = it })
        Text("按夹爪自身方向（不勾 = 按底座方向）", fontSize = 13.sp)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("平移 ${lin.toInt()} mm/s", Modifier.width(110.dp), fontSize = 13.sp)
        Slider(lin, { lin = it; linSpeed = it / 1000.0 }, valueRange = 5f..80f, modifier = Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("转动 ${ang.toInt()} °/s", Modifier.width(110.dp), fontSize = 13.sp)
        Slider(ang, { ang = it; angSpeed = Math.toRadians(it.toDouble()) }, valueRange = 5f..45f, modifier = Modifier.weight(1f))
    }
    val names = listOf("X", "Y", "Z", "自转", "俯仰", "转向")
    for (row in listOf(listOf(0, 1, 2), listOf(5, 4, 3))) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in row) for (sgn in listOf(-1.0, 1.0)) {
                HoldButton("${names[i]}${if (sgn > 0) "+" else "−"}", Modifier.weight(1f)) { held ->
                    if (held) {
                        val v = DoubleArray(6); v[i] = sgn * (if (i < 3) linSpeed else angSpeed)
                        s.heldToolFrame = toolFrame; s.heldToolJog = v
                    } else s.heldToolJog = null
                }
            }
        }
    }
}

@Composable
fun HoldButton(label: String, modifier: Modifier = Modifier, onHold: (Boolean) -> Unit) {
    Box(
        modifier.height(36.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
            .pointerInput(Unit) { detectTapGestures(onPress = { onHold(true); tryAwaitRelease(); onHold(false) }) },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondaryContainer) }
}

@Composable
private fun GripperPanel(s: ArmSession, snap: ArmSession.Snapshot) {
    var g by remember { mutableStateOf(0f) }
    Text("夹爪开合  ${snap.state?.gripper?.let { "%.0f%%".format(it * 100) } ?: "—"}", fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("合", fontSize = 13.sp)
        Slider(g, { g = it }, onValueChangeFinished = { s.setGripper(g.toDouble()) }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        Text("开", fontSize = 13.sp)
    }
}

@Composable
private fun RecordingPanel(s: ArmSession, snap: ArmSession.Snapshot) {
    var name by remember { mutableStateOf("") }
    var byHand by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    Text("轨迹录制", fontWeight = FontWeight.Bold)
    when (snap.recordingState) {
        ArmSession.RecordingState.IDLE -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(byHand, { byHand = it })
                Text("用手掰着录（回零后自动卸力）", fontSize = 13.sp)
            }
            Button({ s.startRecording(byHand) }, Modifier.fillMaxWidth()) { Text("从零位开始录制") }
        }
        ArmSession.RecordingState.GOING_HOME -> Text("正在回零位，到位后自动开始录制…", fontSize = 13.sp)
        else -> {
            Text("● 录制中 ${"%.1f".format(snap.recordingSeconds)} 秒" + if (snap.recordingState == ArmSession.RecordingState.RECORDING_BY_HAND) "（手掰）" else "", color = Color(0xFFE5484D))
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().onFocusTrack(), label = { Text("轨迹名称") }, singleLine = true)
            Button({ s.stopRecording(name); name = "" }, Modifier.fillMaxWidth()) { Text("结束录制并保存") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("回放速度 ×${"%.1f".format(speed)}", Modifier.width(110.dp), fontSize = 13.sp)
        Slider(speed, { speed = it }, valueRange = 0.25f..2f, modifier = Modifier.weight(1f))
    }
    snap.state?.playbackProgress?.let { LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth()) }
    if (snap.recordings.isEmpty()) Text("还没有录制的轨迹", fontSize = 13.sp, color = Color.Gray)
    snap.recordings.forEach { r ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${r.name}  ${"%.1f".format(r.durationS)} 秒", Modifier.weight(1f), fontSize = 13.sp)
            TextButton({ s.play(r.name, speed.toDouble()) }) { Text("播放") }
            TextButton({ s.deleteRecording(r.name) }) { Text("删除", color = Color(0xFFE5484D)) }
        }
    }
}

@Composable
private fun JointsPanel(s: ArmSession, snap: ArmSession.Snapshot) {
    val st = snap.state ?: return
    Text("关节", fontWeight = FontWeight.Bold)
    s.cal.joints.forEachIndexed { i, j ->
        val v = st.q[i]
        val frac = ((v - j.minRad) / (j.maxRad - j.minRad)).coerceIn(0.0, 1.0)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("J${i + 1}", Modifier.width(28.dp), fontSize = 13.sp)
            HoldButton("−", Modifier.width(36.dp)) { held -> s.heldJointJog = if (held) DoubleArray(6).also { it[i] = -0.5 } else null }
            Box(Modifier.weight(1f).padding(horizontal = 6.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.Gray.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(frac.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
            HoldButton("+", Modifier.width(36.dp)) { held -> s.heldJointJog = if (held) DoubleArray(6).also { it[i] = 0.5 } else null }
            Text("${deg(v)}°", Modifier.width(56.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("${st.loads[i]}", Modifier.width(36.dp), fontSize = 11.sp, color = if (kotlin.math.abs(st.loads[i]) > 300) Color(0xFFE5484D) else Color.Gray)
        }
    }
}

private fun mm(v: Double) = "%5.0f".format(v * 1000)
private fun deg(v: Double) = "%4.0f".format(Math.toDegrees(v))
