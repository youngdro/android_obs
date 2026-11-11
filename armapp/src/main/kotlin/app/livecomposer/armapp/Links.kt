package app.livecomposer.armapp

import app.livecomposer.arm.ArmCalibration
import app.livecomposer.arm.ArmLink
import app.livecomposer.arm.StsProtocol
import com.fazecast.jSerialComm.SerialPort
import kotlin.math.abs

/** USB 转舵机总线串口（电脑版）。安卓上换成蓝牙 SPP 的 ArmLink 实现即可。 */
class SerialLink(path: String, baud: Int = 1_000_000) : ArmLink {
    private val port = SerialPort.getCommPort(path).apply {
        setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
        // macOS + CH343 下半阻塞读总是返回 0，用非阻塞轮询
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

    companion object {
        /** 列出可能是舵机总线的串口（macOS 上优先 cu.usbmodem / cu.usbserial / cu.wchusbserial）。 */
        fun ports(): List<String> = SerialPort.getCommPorts().map { it.systemPortPath }
            .filter { !it.contains("Bluetooth") && !it.contains("debug-console") }
            .sortedByDescending { it.contains("usbmodem") || it.contains("usbserial") || it.contains("wchusb") || it.contains("ttyUSB") || it.contains("ttyACM") }
    }
}

/**
 * 模拟机械臂：不接硬件也能试界面。舵机以固定最大速度追目标，卸力时位置不动。
 */
class SimLink(cal: ArmCalibration) : ArmLink {
    private class Servo(var pos: Double, var goal: Int, var torque: Boolean = false)
    private val servos = HashMap<Int, Servo>().apply {
        for (j in cal.joints) put(j.servoId, Servo(j.zeroTick.toDouble(), j.zeroTick))
        cal.gripper?.let { put(it.servoId, Servo(it.closedTick.toDouble(), it.closedTick)) }
    }
    private var rx = ByteArray(0)
    private var last = System.nanoTime()
    private val ticksPerS = 3000.0

    @Synchronized private fun step() {
        val now = System.nanoTime(); val dt = (now - last) / 1e9; last = now
        for (s in servos.values) if (s.torque) {
            val d = s.goal - s.pos; val m = ticksPerS * dt
            s.pos += d.coerceIn(-m, m)
        }
    }

    @Synchronized override fun write(bytes: ByteArray) {
        step()
        val id = bytes[2].toInt() and 0xFF; val inst = bytes[4].toInt() and 0xFF
        val p = bytes.copyOfRange(5, bytes.size - 1)
        fun u16(o: Int) = (p[o].toInt() and 0xFF) or ((p[o + 1].toInt() and 0xFF) shl 8)
        when (inst) {
            StsProtocol.INST_SYNC_WRITE -> {
                val len = p[1].toInt(); var o = 2
                while (o < p.size) { val sid = p[o].toInt(); servos[sid]?.let { it.goal = u16(o + 1); it.torque = true }; o += 1 + len }
            }
            StsProtocol.INST_PING -> servos[id]?.let { reply(id, ByteArray(0)) }
            StsProtocol.INST_WRITE -> servos[id]?.let { s ->
                if (p[0].toInt() == StsProtocol.ADDR_TORQUE_ENABLE) { s.torque = p[1].toInt() == 1; if (!s.torque) s.goal = s.pos.toInt() }
                reply(id, ByteArray(0))
            }
            StsProtocol.INST_READ -> servos[id]?.let { s ->
                val v = when (p[0].toInt()) {
                    StsProtocol.ADDR_PRESENT_POSITION -> s.pos.toInt()
                    StsProtocol.ADDR_PRESENT_LOAD -> (abs(s.goal - s.pos) / 4).toInt().coerceAtMost(200)
                    else -> 0
                }
                reply(id, byteArrayOf((v and 0xFF).toByte(), (v shr 8).toByte()).copyOf(p[1].toInt()))
            }
        }
    }
    private fun reply(id: Int, params: ByteArray) { rx += StsProtocol.packet(id, 0, params) }
    @Synchronized override fun read(timeoutMs: Int): ByteArray { val r = rx; rx = ByteArray(0); return r }
    @Synchronized override fun drain() { rx = ByteArray(0) }
    override fun close() {}
}
