package app.livecomposer.arm

import java.io.IOException

/**
 * 字节流链路：电脑上是 USB 串口，安卓上是 USB OTG 串口或经典蓝牙串口（ESP32 透明转发到舵机总线）。
 * 实现方只需保证 write 按序发送、read 在超时内返回已收到的字节（可能为空）。
 */
interface ArmLink : AutoCloseable {
    @Throws(IOException::class) fun write(bytes: ByteArray)
    @Throws(IOException::class) fun read(timeoutMs: Int): ByteArray
    /** 丢弃尚未读取的接收数据。 */
    fun drain() {}
}

/** 舵机总线的请求/应答封装。只在一个线程里使用。 */
class StsBus(private val link: ArmLink, private val replyTimeoutMs: Int = 30) {

    private fun request(id: Int, pkt: ByteArray): StsProtocol.Response? {
        link.drain()
        link.write(pkt)
        var buf = ByteArray(0)
        val deadline = System.nanoTime() + replyTimeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val chunk = link.read(5)
            if (chunk.isNotEmpty()) buf += chunk
            StsProtocol.parse(buf, id, pkt)?.let { return it }
        }
        return null
    }

    fun ping(id: Int): Boolean = request(id, StsProtocol.ping(id)) != null

    fun read(id: Int, addr: Int, len: Int): ByteArray? =
        request(id, StsProtocol.read(id, addr, len))?.params?.takeIf { it.size == len }

    fun write(id: Int, addr: Int, data: ByteArray): Boolean =
        request(id, StsProtocol.write(id, addr, data)) != null

    fun position(id: Int): Int? = read(id, StsProtocol.ADDR_PRESENT_POSITION, 2)
        ?.let { StsProtocol.signed15(StsProtocol.u16(it[0], it[1])) }

    fun load(id: Int): Int? = read(id, StsProtocol.ADDR_PRESENT_LOAD, 2)
        ?.let { StsProtocol.signedLoad(StsProtocol.u16(it[0], it[1])) }

    fun voltage(id: Int): Double? = read(id, StsProtocol.ADDR_PRESENT_VOLTAGE, 1)?.let { (it[0].toInt() and 0xFF) / 10.0 }

    fun setTorque(id: Int, on: Boolean): Boolean =
        write(id, StsProtocol.ADDR_TORQUE_ENABLE, byteArrayOf(if (on) 1 else 0))

    /** 最大输出力矩，千分比 0..1000（SRAM，断电恢复为 EEPROM 值）。 */
    fun setTorqueLimit(id: Int, permille: Int): Boolean {
        val v = permille.coerceIn(0, 1000)
        return write(id, StsProtocol.ADDR_TORQUE_LIMIT, byteArrayOf((v and 0xFF).toByte(), (v shr 8).toByte()))
    }

    /** 一帧同时给多个舵机下发目标位置（无应答）。 */
    fun syncGoal(goals: Map<Int, Int>, speed: Int = 0) {
        if (goals.isEmpty()) return
        link.write(StsProtocol.syncWrite(StsProtocol.ADDR_GOAL_POSITION, 6,
            goals.map { (id, tick) -> id to StsProtocol.goalData(tick, speed) }))
    }
}
