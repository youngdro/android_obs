package app.livecomposer.rtmp

import java.io.ByteArrayOutputStream

/** 一帧待发送的媒体数据（已是 FLV tag body）。 */
class MediaPacket(
    val type: Type,
    /** 微秒，单调时钟。 */
    val ptsUs: Long,
    val body: ByteArray,
    val isKeyFrame: Boolean,
    val isConfig: Boolean,
) {
    enum class Type { AUDIO, VIDEO }
}

/** H.264 / AAC 与 FLV tag body 之间的转换。 */
object FlvMux {

    /** 在 Annex-B 字节流中切分 NAL 单元（不含起始码）。 */
    fun splitAnnexB(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<ByteArray> {
        val end = offset + length
        val nals = ArrayList<ByteArray>()
        var i = offset
        var start = -1
        while (i + 2 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                if (start >= 0) {
                    var nalEnd = i
                    // 4 字节起始码的前导 0 不属于上一个 NAL
                    while (nalEnd > start && data[nalEnd - 1].toInt() == 0) nalEnd--
                    if (nalEnd > start) nals.add(data.copyOfRange(start, nalEnd))
                }
                i += 3
                start = i
            } else {
                i++
            }
        }
        if (start >= 0 && start < end) nals.add(data.copyOfRange(start, end))
        if (start < 0 && length > 0) {
            // 没有起始码：认为已是单个 NAL
            nals.add(data.copyOfRange(offset, end))
        }
        return nals
    }

    fun nalType(nal: ByteArray): Int = nal[0].toInt() and 0x1F

    /** AVCDecoderConfigurationRecord → FLV video sequence header tag body。 */
    fun avcSequenceHeader(sps: ByteArray, pps: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0x17) // keyframe + AVC
        o.write(0x00) // AVC sequence header
        o.write(0); o.write(0); o.write(0) // composition time
        o.write(0x01)
        o.write(sps[1].toInt()); o.write(sps[2].toInt()); o.write(sps[3].toInt())
        o.write(0xFF) // 4 字节 NAL 长度
        o.write(0xE1) // 1 个 SPS
        o.write((sps.size shr 8) and 0xFF); o.write(sps.size and 0xFF); o.write(sps)
        o.write(0x01)
        o.write((pps.size shr 8) and 0xFF); o.write(pps.size and 0xFF); o.write(pps)
        return o.toByteArray()
    }

    /** 一帧 NAL 列表 → FLV video NALU tag body（AVCC 长度前缀）。会跳过 AUD 和参数集。 */
    fun avcFrame(nals: List<ByteArray>, keyFrame: Boolean, compositionTimeMs: Int = 0): ByteArray? {
        val payload = nals.filter { val t = nalType(it); t != 9 && t != 7 && t != 8 }
        if (payload.isEmpty()) return null
        val o = ByteArrayOutputStream(payload.sumOf { it.size + 4 } + 5)
        o.write(if (keyFrame) 0x17 else 0x27)
        o.write(0x01)
        o.write((compositionTimeMs shr 16) and 0xFF)
        o.write((compositionTimeMs shr 8) and 0xFF)
        o.write(compositionTimeMs and 0xFF)
        for (n in payload) {
            o.write((n.size ushr 24) and 0xFF); o.write((n.size shr 16) and 0xFF)
            o.write((n.size shr 8) and 0xFF); o.write(n.size and 0xFF)
            o.write(n)
        }
        return o.toByteArray()
    }

    /** AudioSpecificConfig（MediaCodec csd-0）→ FLV AAC sequence header tag body。 */
    fun aacSequenceHeader(asc: ByteArray): ByteArray = byteArrayOf(0xAF.toByte(), 0x00) + asc

    /** 原始 AAC 帧（无 ADTS 头）→ FLV AAC raw tag body。 */
    fun aacFrame(raw: ByteArray, offset: Int = 0, length: Int = raw.size - offset): ByteArray {
        val b = ByteArray(length + 2)
        b[0] = 0xAF.toByte(); b[1] = 0x01
        System.arraycopy(raw, offset, b, 2, length)
        return b
    }

    /** 由采样率/声道生成 AAC-LC AudioSpecificConfig。 */
    fun aacLcConfig(sampleRate: Int, channels: Int): ByteArray {
        val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        val idx = rates.indexOf(sampleRate).let { if (it < 0) 4 else it }
        val objectType = 2
        val v = (objectType shl 11) or (idx shl 7) or (channels shl 3)
        return byteArrayOf((v shr 8).toByte(), v.toByte())
    }
}
