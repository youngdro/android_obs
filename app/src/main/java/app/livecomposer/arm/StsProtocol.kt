package app.livecomposer.arm

/**
 * 飞特 STS/SMS 总线舵机协议（ST3215 等）的报文编解码。
 *
 * 报文：FF FF id len inst params… checksum，len = params + 2，checksum = ~(id + len + inst + params) & 0xFF。
 * 应答：FF FF id len error params… checksum。
 */
object StsProtocol {
    const val BROADCAST = 0xFE
    const val INST_PING = 0x01
    const val INST_READ = 0x02
    const val INST_WRITE = 0x03
    const val INST_SYNC_WRITE = 0x83

    // 内存表
    const val ADDR_TORQUE_ENABLE = 40
    const val ADDR_ACC = 41
    const val ADDR_GOAL_POSITION = 42
    const val ADDR_GOAL_SPEED = 46
    const val ADDR_TORQUE_LIMIT = 48
    const val ADDR_PRESENT_POSITION = 56
    const val ADDR_PRESENT_LOAD = 60
    const val ADDR_PRESENT_VOLTAGE = 62
    const val ADDR_PRESENT_TEMPERATURE = 63
    const val ADDR_MOVING = 66

    fun packet(id: Int, inst: Int, params: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(params.size + 6)
        out[0] = 0xFF.toByte(); out[1] = 0xFF.toByte()
        out[2] = id.toByte(); out[3] = (params.size + 2).toByte(); out[4] = inst.toByte()
        params.copyInto(out, 5)
        var sum = id + params.size + 2 + inst
        for (b in params) sum += b.toInt() and 0xFF
        out[out.size - 1] = (sum.inv() and 0xFF).toByte()
        return out
    }

    fun ping(id: Int) = packet(id, INST_PING)
    fun read(id: Int, addr: Int, len: Int) = packet(id, INST_READ, byteArrayOf(addr.toByte(), len.toByte()))
    fun write(id: Int, addr: Int, data: ByteArray) = packet(id, INST_WRITE, byteArrayOf(addr.toByte()) + data)

    /** 同步写：一帧给多个舵机写同一段寄存器，所有舵机同时生效。 */
    fun syncWrite(addr: Int, dataLen: Int, items: List<Pair<Int, ByteArray>>): ByteArray {
        val params = ArrayList<Byte>()
        params += addr.toByte(); params += dataLen.toByte()
        for ((id, data) in items) {
            require(data.size == dataLen) { "同步写数据长度不一致" }
            params += id.toByte(); params.addAll(data.toList())
        }
        return packet(BROADCAST, INST_SYNC_WRITE, params.toByteArray())
    }

    /** 目标位置 + 时间 + 速度（地址 42 起 6 字节）。speed = 0 表示最大速度，用于高频设定点跟随。 */
    fun goalData(tick: Int, speed: Int = 0): ByteArray =
        byteArrayOf(lo(tick), hi(tick), 0, 0, lo(speed), hi(speed))

    fun u16(lo: Byte, hi: Byte): Int = (lo.toInt() and 0xFF) or ((hi.toInt() and 0xFF) shl 8)

    /** 位置寄存器第 15 位是符号位。 */
    fun signed15(v: Int): Int = if (v and 0x8000 != 0) -(v and 0x7FFF) else v

    /** 负载寄存器第 10 位是方向位，数值 0..1000（千分比）。 */
    fun signedLoad(v: Int): Int = (v and 0x3FF) * (if (v and 0x400 != 0) -1 else 1)

    private fun lo(v: Int) = (v and 0xFF).toByte()
    private fun hi(v: Int) = ((v shr 8) and 0xFF).toByte()

    class Response(val id: Int, val error: Int, val params: ByteArray)

    /**
     * 在接收缓冲里找到 [id] 的一条完整应答。跳过半双工转接板回显的自己发出的报文 [sent]。
     * 返回 null 表示还不完整或没有。
     */
    fun parse(buf: ByteArray, id: Int, sent: ByteArray?): Response? {
        var i = 0
        while (i + 6 <= buf.size) {
            if (buf[i] != 0xFF.toByte() || buf[i + 1] != 0xFF.toByte()) { i++; continue }
            if (sent != null && i + sent.size <= buf.size && buf.copyOfRange(i, i + sent.size).contentEquals(sent)) {
                i += sent.size; continue
            }
            val pid = buf[i + 2].toInt() and 0xFF
            val len = buf[i + 3].toInt() and 0xFF
            if (len < 2) { i += 2; continue }
            val end = i + 4 + len
            if (end > buf.size) return null
            var sum = 0
            for (k in i + 2 until end - 1) sum += buf[k].toInt() and 0xFF
            if ((sum.inv() and 0xFF) != (buf[end - 1].toInt() and 0xFF) || pid != id) { i += 2; continue }
            return Response(pid, buf[i + 4].toInt() and 0xFF, buf.copyOfRange(i + 5, end - 1))
        }
        return null
    }
}
