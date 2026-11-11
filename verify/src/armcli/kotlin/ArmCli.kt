import app.livecomposer.arm.*
import com.fazecast.jSerialComm.SerialPort
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.PI

/** 电脑上的 USB 串口链路（jSerialComm），安卓上换成 OTG 串口或蓝牙即可，控制核心不变。 */
class SerialLink(path: String, baud: Int = 1_000_000) : ArmLink {
    private val port = SerialPort.getCommPort(path).apply {
        setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
        // macOS + CH343 下半阻塞读总是返回 0，改为非阻塞轮询（2026-11-11 实机验证）
        setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0)
        require(openPort()) { "打不开串口 $path" }
    }
    private val buf = ByteArray(256)
    override fun write(bytes: ByteArray) { port.writeBytes(bytes, bytes.size) }
    override fun read(timeoutMs: Int): ByteArray {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val av = port.bytesAvailable()
            if (av > 0) {
                val n = port.readBytes(buf, minOf(av, buf.size))
                return if (n > 0) buf.copyOf(n) else ByteArray(0)
            }
            if (System.nanoTime() >= deadline) return ByteArray(0)
            Thread.sleep(0, 200_000)
        }
    }
    override fun drain() { while (port.bytesAvailable() > 0) port.readBytes(buf, minOf(buf.size, port.bytesAvailable())) }
    override fun close() { port.closePort() }
}

private fun deg(r: Double) = "%.1f".format(r * 180 / PI)

/**
 * 有线调试控制台：gradle -p verify armCli -Pport=/dev/cu.usbmodemXXXX -Pcal=calib.json
 *
 * 命令（角度单位：度）：
 *   status                      读当前关节角、夹爪、负载
 *   on [力矩%]                  上力（默认 40%）
 *   off                         卸力（先扶住！）
 *   move a1 a2 a3 a4 a5 a6 [秒]  同步移动到关节角
 *   joint i 角度 [秒]           只改第 i 个关节（1..6）
 *   grip 0..1                   夹爪开合
 *   home [秒]                   回零位
 *   level on|off                大臂/小臂动时手腕补偿保持朝向
 *   jog i 速度比例 秒           遥控关节 i 匀速转一段时间（-1..1）
 *   rec 名称 / key [秒] [停留秒] / save    示教：卸力后用手摆姿态，key 记一帧
 *   play 名称                   回放预设
 *   stop                        原地停住
 *   quit
 */
fun main(args: Array<String>) {
    val port = args.getOrNull(0) ?: error("需要串口路径")
    val calFile = args.getOrNull(1)?.let(::File)
    val cal = calFile?.takeIf { it.exists() }?.let { ArmCalibration.fromJson(JSONObject(it.readText())) } ?: ArmCalibration.A003_ARM2
    val presetFile = File(args.getOrNull(2) ?: "arm_presets.json")
    val presets = LinkedHashMap<String, Preset>()
    if (presetFile.exists()) JSONObject(presetFile.readText()).let { o ->
        for (name in o.keys()) {
            val arr = o.getJSONArray(name)
            presets[name] = Preset(name, (0 until arr.length()).map { i ->
                val f = arr.getJSONObject(i); val q = f.getJSONArray("q")
                Keyframe(DoubleArray(6) { q.getDouble(it) }, f.optDouble("gripper").takeIf { !it.isNaN() }, f.getDouble("durationS"), f.optDouble("holdS", 0.0))
            })
        }
    }
    fun savePresets() {
        val o = JSONObject()
        for ((n, p) in presets) o.put(n, JSONArray().apply { p.frames.forEach { f ->
            put(JSONObject().put("q", JSONArray(f.q.toList())).put("gripper", f.gripper ?: JSONObject.NULL).put("durationS", f.durationS).put("holdS", f.holdS))
        } })
        presetFile.writeText(o.toString(2))
    }

    val link = SerialLink(port)
    val bus = StsBus(link)
    var cfg = ArmController.Config()
    var arm = ArmController(bus, cal, cfg) { println("[arm] $it") }
    val cmds = LinkedBlockingQueue<String>()
    var running = true
    var recording: Pair<String, MutableList<Keyframe>>? = null
    var jogLeft = 0.0; var jogVec = DoubleArray(6)

    Thread {
        while (running) { val l = readlnOrNull() ?: "quit"; cmds.put(l.trim()) }
    }.apply { isDaemon = true }.start()

    println("已连接 $port，标定：${calFile ?: "内置 A003 第二台（含大臂/手腕避让包络）"}。输入 help 查看命令。")
    var last = System.nanoTime()
    while (running) {
        val now = System.nanoTime(); val dt = (now - last) / 1e9; last = now
        if (jogLeft > 0) { arm.jog(jogVec); jogLeft -= dt }
        arm.tick(dt)
        cmds.poll()?.let { line ->
            val p = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            try {
                when (p.firstOrNull()) {
                    null -> {}
                    "help" -> println("status / on [力矩%] / off / move a1..a6 [秒] / joint i 角度 [秒] / grip 0..1 / home [秒] / level on|off / jog i 比例 秒 / rec 名称 / key [秒] [停留] / save / play 名称 / list / stop / quit")
                    "status" -> {
                        if (arm.state.mode == ArmController.Mode.OFF && !arm.readPose()) println("读位置失败：舵机无应答，检查供电和串口")
                        else arm.state.let { s -> println("模式 ${s.mode}${s.fault?.let { "（$it）" } ?: ""}  关节 ${s.q.joinToString(" ") { deg(it) }}  夹爪 ${s.gripper?.let { "%.2f".format(it) }}  负载 ${s.loads.joinToString(" ")}") }
                    }
                    "on" -> {
                        val pct = p.getOrNull(1)?.toInt() ?: 40
                        cfg = cfg.copy(torqueLimitPermille = pct * 10)
                        arm = ArmController(bus, cal, cfg) { println("[arm] $it") }
                        println(if (arm.enable()) "已上力（力矩上限 $pct%）" else "上力失败：${arm.state.fault}")
                    }
                    "off" -> arm.disable()
                    "move" -> {
                        val q = DoubleArray(6) { p[it + 1].toDouble() * PI / 180 }
                        arm.moveTo(q, durationS = p.getOrNull(7)?.toDouble())
                    }
                    "joint" -> {
                        val i = p[1].toInt() - 1
                        val q = arm.state.q.copyOf(); q[i] = p[2].toDouble() * PI / 180
                        arm.moveTo(q, durationS = p.getOrNull(3)?.toDouble())
                    }
                    "grip" -> arm.moveTo(arm.state.q, gripper = p[1].toDouble(), durationS = 0.8)
                    "home" -> arm.moveTo(DoubleArray(6), durationS = p.getOrNull(1)?.toDouble() ?: 3.0)
                    "level" -> {
                        cfg = cfg.copy(keepLevel = p.getOrNull(1) == "on")
                        val wasOn = arm.state.mode != ArmController.Mode.OFF
                        arm = ArmController(bus, cal, cfg) { println("[arm] $it") }
                        if (wasOn) arm.enable()
                        println("手腕补偿 ${if (cfg.keepLevel) "开" else "关"}")
                    }
                    "jog" -> { jogVec = DoubleArray(6); jogVec[p[1].toInt() - 1] = p[2].toDouble(); jogLeft = p[3].toDouble() }
                    "rec" -> { recording = p[1] to mutableListOf(); println("开始录制 ${p[1]}：卸力后用手摆姿态，每摆好一帧输入 key [用时秒] [停留秒]") }
                    "key" -> recording?.let { (_, frames) ->
                        arm.readPose(); val s = arm.state
                        frames += Keyframe(s.q.copyOf(), s.gripper, p.getOrNull(1)?.toDouble() ?: 2.0, p.getOrNull(2)?.toDouble() ?: 0.0)
                        println("第 ${frames.size} 帧：${s.q.joinToString(" ") { deg(it) }}")
                    } ?: println("先 rec 名称")
                    "save" -> recording?.let { (n, frames) -> presets[n] = Preset(n, frames.toList()); savePresets(); recording = null; println("已保存 $n（${frames.size} 帧）到 $presetFile") }
                    "list" -> println(presets.keys.joinToString().ifEmpty { "（没有预设）" })
                    "play" -> presets[p[1]]?.let { arm.play(it) } ?: println("没有预设 ${p[1]}")
                    "stop" -> { jogLeft = 0.0; arm.stop() }
                    "clear" -> arm.clearFault()
                    "quit" -> running = false
                    else -> println("不认识的命令，输入 help")
                }
            } catch (e: Exception) { println("命令有误：${e.message}") }
        }
        val spent = (System.nanoTime() - now) / 1_000_000
        Thread.sleep(maxOf(1, 20 - spent))
    }
    arm.stop()
    link.close()
    println("已退出（舵机保持当前状态；需要卸力请先 off）")
}
