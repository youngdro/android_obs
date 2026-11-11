package app.livecomposer.armapp

import app.livecomposer.arm.ArmCalibration
import app.livecomposer.arm.ArmController
import app.livecomposer.arm.ArmIk
import app.livecomposer.arm.ArmLink
import app.livecomposer.arm.Recording
import app.livecomposer.arm.StsBus
import app.livecomposer.arm.ToolPose
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 界面和控制核心之间的一层：独立的控制线程以约 50Hz 调用 [ArmController.tick]，界面的操作排队交给这个线程执行
 * （控制器不是线程安全的），状态以快照形式给界面读。
 *
 * 不依赖任何界面库，安卓上可以原样复用（把 [SerialLink] 换成蓝牙链路）。
 */
class ArmSession(val cal: ArmCalibration, private val dataDir: File) {
    data class Snapshot(
        val link: String? = null,
        val state: ArmController.State? = null,
        val torquePct: Int = 55,
        val tool: ToolPose? = null,
        val toolTarget: ToolPose? = null,
        val recordings: List<Recording> = emptyList(),
        val recordingState: RecordingState = RecordingState.IDLE,
        val recordingSeconds: Double = 0.0,
        val message: String? = null,
    )

    enum class RecordingState { IDLE, GOING_HOME, RECORDING, RECORDING_BY_HAND }

    @Volatile var snapshot = Snapshot(recordings = loadRecordings()); private set

    /** 按住点动按钮/按键时设置，松开时清空；控制线程每个周期都会续上。 */
    @Volatile var heldToolJog: DoubleArray? = null
    @Volatile var heldToolFrame = false
    @Volatile var heldJointJog: DoubleArray? = null

    private val commands = ConcurrentLinkedQueue<(ArmController) -> Unit>()
    private var link: ArmLink? = null
    private var arm: ArmController? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var torquePct = 55
    private var message: String? = null
    private var recState = RecordingState.IDLE
    private var recHand = false
    private var recStartedAt = 0L
    private var recordings = snapshot.recordings.toMutableList()
    private var lastTarget: ToolPose? = null

    val ik = ArmIk(cal)

    fun connect(port: String) {
        disconnect()
        val l = try { if (port == SIM) SimLink(cal) else SerialLink(port) } catch (e: Exception) { say("连接失败：${e.message}"); return }
        link = l
        val a = ArmController(StsBus(l), cal, ArmController.Config(torqueLimitPermille = torquePct * 10, abortLoad = maxOf(400, torquePct * 10 - 50))) { say(it) }
        arm = a
        running = true
        thread = Thread({ loop(a, port) }, "arm-control").apply { isDaemon = true; start() }
    }

    fun disconnect() {
        running = false
        thread?.join(1000); thread = null
        link?.close(); link = null; arm = null
        snapshot = snapshot.copy(link = null, state = null, tool = null)
    }

    private fun post(c: (ArmController) -> Unit) { commands += c }

    fun enable(pct: Int) {
        torquePct = pct.coerceIn(20, 90)
        val l = link ?: return
        // 力矩上限变了要换一个控制器实例（配置是不可变的）
        post { old ->
            if (old.state.mode != ArmController.Mode.OFF) old.stop()
            val a = ArmController(StsBus(l), cal, ArmController.Config(torqueLimitPermille = torquePct * 10, abortLoad = maxOf(400, torquePct * 10 - 50))) { say(it) }
            arm = a
            a.setGripperForce(gripperForcePct * 10)
            a.setGripSqueeze(gripSqueezePct / 100.0)
            a.carryGripFrom(old)
            if (a.enable()) say("已上力（力矩上限 $torquePct%）") else say("上力失败：${a.state.fault}")
        }
    }
    fun disable() = post { it.disable() }
    fun stop() { heldToolJog = null; heldJointJog = null; post { it.stop("急停：原地保持"); recState = if (recState == RecordingState.GOING_HOME) RecordingState.IDLE else recState } }
    fun clearFault() = post { it.clearFault() }
    fun home() = post { it.moveTo(ik.project(DoubleArray(6))) }
    fun setGripper(open: Double) = post { it.setGripper(open) }
    /** 握持：慢慢合上直到夹紧，然后锁定（回放/移动/卸力都不松开）。 */
    fun grasp() = post { if (!it.grasp()) say("握持需要先上力") }
    fun releaseGrasp() = post { it.releaseGrasp() }

    fun moveToPose(p: ToolPose) = post { a ->
        lastTarget = p
        val r = a.moveToPose(p) ?: return@post say("先上力")
        if (r.reached) say("前往目标（逆解 ${r.iterations} 步）")
        else say("够不着：最近还差 ${"%.0f".format(r.posErr * 1000)} mm / ${"%.0f".format(Math.toDegrees(r.rotErr))}°，没有移动")
    }

    /** 从零位开始录制。[byHand] 为真：回零后卸力，用手掰着录；否则保持上力，用界面操控录。 */
    fun startRecording(byHand: Boolean) = post { a ->
        if (recState != RecordingState.IDLE) return@post
        recHand = byHand
        if (a.state.mode == ArmController.Mode.OFF) {
            if (!byHand) return@post say("用界面操控录制需要先上力")
            say("已卸力：请确认机械臂在零位，录制开始，用手移动")
            a.startRecording(); recState = RecordingState.RECORDING_BY_HAND; recStartedAt = System.nanoTime()
        } else {
            a.moveTo(ik.project(DoubleArray(6))); recState = RecordingState.GOING_HOME; say("先回零位，到位后开始录制")
        }
    }

    fun stopRecording(name: String) = post { a ->
        if (recState == RecordingState.GOING_HOME) { recState = RecordingState.IDLE; return@post }
        val r = a.stopRecording(name.ifBlank { "轨迹 ${recordings.size + 1}" })
        recState = RecordingState.IDLE
        if (r == null) return@post say("录制太短，没有保存")
        recordings.removeAll { it.name == r.name }
        recordings += r
        saveRecordings()
        say("已保存「${r.name}」，${"%.1f".format(r.durationS)} 秒")
    }

    fun play(name: String, speed: Double, holdGripper: Boolean, maxVelDeg: Int) = post { a ->
        val r = recordings.firstOrNull { it.name == name } ?: return@post
        message = null
        val ok = a.playRecording(r, speed, holdGripper, Math.toRadians(maxVelDeg.toDouble()))
        val note = message
        if (ok) say("回放「$name」：先回到起点" + (if (holdGripper) "（夹爪保持当前夹持）" else "") + (note?.let { "。$it" } ?: ""))
        else say("回放需要先上力")
    }

    @Volatile var gripperForcePct = 40; private set
    fun setGripperForce(pct: Int) { gripperForcePct = pct; post { it.setGripperForce(pct * 10) } }
    /** 压紧量（夹爪全程的百分比），锁定中调整立即生效。 */
    @Volatile var gripSqueezePct = 10; private set
    fun setGripSqueeze(pct: Int) { gripSqueezePct = pct; post { it.setGripSqueeze(pct / 100.0) } }

    fun deleteRecording(name: String) = post { recordings.removeAll { it.name == name }; saveRecordings() }

    private fun say(m: String) { message = m }

    private fun loop(a0: ArmController, port: String) {
        var last = System.nanoTime()
        var n = 0
        var prevMode: ArmController.Mode? = null
        while (running) {
            val a = arm ?: break
            while (true) { val c = commands.poll() ?: break; try { c(a) } catch (e: Exception) { say("出错：${e.message}") } }
            val armNow = arm ?: break
            heldToolJog?.let { armNow.jogTool(it, heldToolFrame) }
            heldJointJog?.let { armNow.jog(it) }
            val now = System.nanoTime(); val dt = ((now - last) / 1e9).coerceAtMost(0.1); last = now
            val st0 = armNow.state
            if (st0.mode == ArmController.Mode.OFF && recState == RecordingState.IDLE && n % 5 == 0) armNow.readPose()
            armNow.tick(dt)
            val modeNow = armNow.state.mode
            if (prevMode == ArmController.Mode.PLAYBACK && modeNow == ArmController.Mode.IDLE) say("回放结束")
            if (prevMode == ArmController.Mode.MOVING && modeNow == ArmController.Mode.IDLE && message?.startsWith("前往") == true) say("已到达")
            prevMode = modeNow
            if (recState == RecordingState.GOING_HOME && armNow.state.mode == ArmController.Mode.IDLE) {
                if (recHand) armNow.disable()
                armNow.startRecording(); recStartedAt = System.nanoTime()
                recState = if (recHand) RecordingState.RECORDING_BY_HAND else RecordingState.RECORDING
                say(if (recHand) "已在零位卸力，开始录制：用手移动机械臂" else "开始录制：用界面操控机械臂")
            }
            if (n++ % 2 == 0) {
                val st = armNow.state
                val q = if (st.mode == ArmController.Mode.OFF) st.q else st.setpoint
                snapshot = Snapshot(
                    link = port, state = st, torquePct = torquePct,
                    tool = ik.forward(q), toolTarget = lastTarget,
                    recordings = recordings.toList(), recordingState = recState,
                    recordingSeconds = if (recState == RecordingState.RECORDING || recState == RecordingState.RECORDING_BY_HAND) (System.nanoTime() - recStartedAt) / 1e9 else 0.0,
                    message = message,
                )
            }
            val spent = (System.nanoTime() - now) / 1_000_000
            if (spent < 20) Thread.sleep(20 - spent)
        }
    }

    private val recFile get() = File(dataDir, "recordings.json")

    private fun loadRecordings(): List<Recording> = try {
        if (recFile.exists()) Recording.listFromJson(org.json.JSONObject(recFile.readText())) else emptyList()
    } catch (e: Exception) { emptyList() }

    private fun saveRecordings() {
        dataDir.mkdirs()
        recFile.writeText(Recording.listToJson(recordings).toString(1))
    }

    companion object { const val SIM = "模拟机械臂（不接硬件）" }
}
