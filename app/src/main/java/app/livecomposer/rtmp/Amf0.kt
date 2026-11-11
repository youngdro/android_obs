package app.livecomposer.rtmp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** 最小 AMF0 编解码，覆盖 RTMP 推流所需的命令与元数据。 */
object Amf0 {
    private const val NUMBER = 0x00
    private const val BOOLEAN = 0x01
    private const val STRING = 0x02
    private const val OBJECT = 0x03
    private const val NULL = 0x05
    private const val UNDEFINED = 0x06
    private const val REFERENCE = 0x07
    private const val ECMA_ARRAY = 0x08
    private const val OBJECT_END = 0x09
    private const val STRICT_ARRAY = 0x0A
    private const val DATE = 0x0B
    private const val LONG_STRING = 0x0C

    /** 有序 Map 作为 ECMA Array 写出（onMetaData 需要）。 */
    class EcmaArray(val map: Map<String, Any?>)

    fun encode(vararg values: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        values.forEach { write(out, it) }
        return out.toByteArray()
    }

    private fun write(out: ByteArrayOutputStream, v: Any?) {
        when (v) {
            null -> out.write(NULL)
            is Number -> {
                out.write(NUMBER)
                out.write(ByteBuffer.allocate(8).putDouble(v.toDouble()).array())
            }
            is Boolean -> {
                out.write(BOOLEAN); out.write(if (v) 1 else 0)
            }
            is String -> {
                val b = v.toByteArray(Charsets.UTF_8)
                if (b.size > 0xFFFF) {
                    out.write(LONG_STRING); writeU32(out, b.size)
                } else {
                    out.write(STRING); writeU16(out, b.size)
                }
                out.write(b)
            }
            is EcmaArray -> {
                out.write(ECMA_ARRAY); writeU32(out, v.map.size)
                writeProps(out, v.map)
            }
            is Map<*, *> -> {
                out.write(OBJECT)
                @Suppress("UNCHECKED_CAST")
                writeProps(out, v as Map<String, Any?>)
            }
            else -> throw IllegalArgumentException("AMF0 不支持的类型: ${v::class}")
        }
    }

    private fun writeProps(out: ByteArrayOutputStream, map: Map<String, Any?>) {
        for ((k, value) in map) {
            val kb = k.toByteArray(Charsets.UTF_8)
            writeU16(out, kb.size); out.write(kb)
            write(out, value)
        }
        writeU16(out, 0); out.write(OBJECT_END)
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF); out.write(v and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF); out.write((v shr 16) and 0xFF)
        out.write((v shr 8) and 0xFF); out.write(v and 0xFF)
    }

    /** 解码全部值；遇到不认识的类型就停止（返回已解析部分）。 */
    fun decodeAll(data: ByteArray, offset: Int = 0): List<Any?> {
        val buf = ByteBuffer.wrap(data, offset, data.size - offset)
        val result = ArrayList<Any?>()
        try {
            while (buf.hasRemaining()) result.add(read(buf))
        } catch (_: Exception) {
            // 截断或不支持的类型：返回已解析的部分
        }
        return result
    }

    private object End

    private fun read(buf: ByteBuffer): Any? {
        return when (val t = buf.get().toInt() and 0xFF) {
            NUMBER -> buf.getDouble()
            BOOLEAN -> buf.get().toInt() != 0
            STRING -> readStr(buf, buf.getShort().toInt() and 0xFFFF)
            LONG_STRING -> readStr(buf, buf.getInt())
            OBJECT -> readProps(buf)
            ECMA_ARRAY -> { buf.getInt(); readProps(buf) }
            NULL, UNDEFINED -> null
            REFERENCE -> { buf.getShort(); null }
            OBJECT_END -> End
            STRICT_ARRAY -> {
                val n = buf.getInt()
                List(n) { read(buf) }
            }
            DATE -> { buf.getDouble(); buf.getShort(); null }
            else -> throw IllegalStateException("AMF0 type $t")
        }
    }

    private fun readStr(buf: ByteBuffer, len: Int): String {
        val b = ByteArray(len); buf.get(b); return String(b, Charsets.UTF_8)
    }

    private fun readProps(buf: ByteBuffer): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        while (true) {
            val key = readStr(buf, buf.getShort().toInt() and 0xFFFF)
            val v = read(buf)
            if (key.isEmpty() && v === End) break
            m[key] = v
        }
        return m
    }
}
