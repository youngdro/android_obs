package app.livecomposer.rtmp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Random
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** 推流错误分类，对应需求中的“地址错误、连接超时、服务器拒绝、网络中断、编码失败”等。 */
enum class RtmpErrorKind(val label: String, val retryable: Boolean) {
    BAD_URL("地址错误", false),
    DNS("域名解析失败", true),
    TIMEOUT("连接超时", true),
    REFUSED_TCP("无法连接服务器", true),
    TLS("TLS/证书错误", false),
    SERVER_REJECTED("服务器拒绝", false),
    NETWORK("网络中断", true),
    ENCODER("编码失败", false),
}

class RtmpException(val kind: RtmpErrorKind, message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * 单个 RTMP 发布连接（阻塞式）。线程模型：
 * - connect()/publish() 在发送线程调用；
 * - 之后由内部读线程处理服务器下行消息（ping、ack、onStatus）；
 * - writeMedia() 只能在发送线程调用。
 */
class RtmpConnection(
    private val url: RtmpUrl,
    private val connectTimeoutMs: Int = 10_000,
    private val sslFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val log: (String) -> Unit = {},
) {
    private var socket: Socket? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private val writeLock = Any()

    private var outChunkSize = 128
    private var inChunkSize = 128
    private var windowAckSize = 2_500_000L
    private var bytesRead = 0L
    private var lastAck = 0L
    private var streamId = 0
    private var txn = 0

    @Volatile var bytesWritten = 0L; private set
    @Volatile var closed = false; private set

    /** 读线程检测到的致命错误（服务器断开、onStatus 错误等）。 */
    @Volatile var asyncError: RtmpException? = null; private set
    /** 当前阻塞写开始的时间（ms），0 表示没有在写；供看门狗检测写阻塞。 */
    @Volatile var writeStartedAt = 0L; private set

    private val inHeaders = HashMap<Int, InHeader>()

    private class InHeader {
        var timestamp = 0L
        var timestampDelta = 0L
        var length = 0
        var typeId = 0
        var streamId = 0
        var extended = false
        var buffer: ByteArray? = null
        var filled = 0
    }

    private class Message(val typeId: Int, val streamId: Int, val timestamp: Long, val payload: ByteArray)

    fun connectAndPublish() {
        openSocket()
        handshake()
        setChunkSize(4096)
        commandConnect()
        streamId = commandCreateStream()
        commandPublish()
        socket!!.soTimeout = 0
        Thread({ readLoop() }, "rtmp-reader").apply { isDaemon = true }.start()
    }

    // ---------------- socket ----------------

    private fun openSocket() {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.sendBufferSize = 512 * 1024
            s.connect(InetSocketAddress(url.host, url.port), connectTimeoutMs)
        } catch (e: UnknownHostException) {
            s.close(); throw RtmpException(RtmpErrorKind.DNS, "无法解析服务器地址 ${url.host}", e)
        } catch (e: SocketTimeoutException) {
            s.close(); throw RtmpException(RtmpErrorKind.TIMEOUT, "连接 ${url.host}:${url.port} 超时", e)
        } catch (e: ConnectException) {
            s.close(); throw RtmpException(RtmpErrorKind.REFUSED_TCP, "服务器 ${url.host}:${url.port} 拒绝连接", e)
        } catch (e: NoRouteToHostException) {
            s.close(); throw RtmpException(RtmpErrorKind.NETWORK, "网络不可达", e)
        } catch (e: IOException) {
            s.close(); throw RtmpException(RtmpErrorKind.NETWORK, "连接失败：${e.message}", e)
        }
        var sock: Socket = s
        if (url.secure) {
            try {
                val ssl = sslFactory.createSocket(s, url.host, url.port, true) as SSLSocket
                val params = ssl.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS" // 校验证书主机名
                ssl.sslParameters = params
                ssl.soTimeout = connectTimeoutMs
                ssl.startHandshake()
                sock = ssl
            } catch (e: SSLException) {
                s.close()
                val msg = if (e is SSLHandshakeException) "证书校验失败或 TLS 握手失败" else "TLS 错误"
                throw RtmpException(RtmpErrorKind.TLS, "$msg：${e.message}", e)
            } catch (e: IOException) {
                s.close(); throw RtmpException(RtmpErrorKind.NETWORK, "TLS 连接失败：${e.message}", e)
            }
        }
        sock.soTimeout = connectTimeoutMs
        socket = sock
        input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
        output = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            if (streamId != 0) {
                sendCommand(3, 0, "deleteStream", 0, null, streamId)
            }
        } catch (_: Exception) {
        }
        try { socket?.close() } catch (_: Exception) {}
    }

    // ---------------- handshake ----------------

    private fun handshake() {
        try {
            val c1 = ByteArray(1536)
            Random().nextBytes(c1)
            for (i in 0 until 8) c1[i] = 0 // time + zero
            output.write(3); output.write(c1); output.flush()
            val s0 = input.read()
            if (s0 < 0) throw EOFException()
            if (s0 != 3) throw RtmpException(RtmpErrorKind.SERVER_REJECTED, "服务器握手版本不支持（$s0）")
            val s1 = readFully(1536)
            output.write(s1); output.flush() // C2 = echo S1
            readFully(1536) // S2
        } catch (e: RtmpException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw RtmpException(RtmpErrorKind.TIMEOUT, "RTMP 握手超时", e)
        } catch (e: IOException) {
            throw RtmpException(RtmpErrorKind.NETWORK, "RTMP 握手失败：${e.message}", e)
        }
    }

    private fun readFully(n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw EOFException("连接被服务器关闭")
            off += r
        }
        bytesRead += n
        return b
    }

    // ---------------- commands ----------------

    private fun commandConnect() {
        val id = ++txn
        val obj = linkedMapOf<String, Any?>(
            "app" to url.app,
            "type" to "nonprivate",
            "flashVer" to "FMLE/3.0 (compatible; LiveComposer)",
            "tcUrl" to url.tcUrl,
        )
        sendCommand(3, 0, "connect", id, obj)
        val reply = awaitResult(id)
        if (reply.first == "_error") {
            throw RtmpException(RtmpErrorKind.SERVER_REJECTED, "服务器拒绝连接应用 ${url.app}：${describe(reply.second)}")
        }
    }

    private fun commandCreateStream(): Int {
        sendCommand(3, 0, "releaseStream", ++txn, null, url.streamName)
        sendCommand(3, 0, "FCPublish", ++txn, null, url.streamName)
        val id = ++txn
        sendCommand(3, 0, "createStream", id, null)
        val reply = awaitResult(id)
        if (reply.first == "_error") {
            throw RtmpException(RtmpErrorKind.SERVER_REJECTED, "服务器拒绝 createStream：${describe(reply.second)}")
        }
        return (reply.second.getOrNull(3) as? Double)?.toInt() ?: 1
    }

    private fun commandPublish() {
        sendCommand(8, streamId, "publish", ++txn, null, url.streamName, "live")
        while (true) {
            val msg = readCommandMessage()
            val values = msg.second
            when (msg.first) {
                "onStatus" -> {
                    val info = values.getOrNull(3) as? Map<*, *>
                    val code = info?.get("code") as? String ?: ""
                    when {
                        code == "NetStream.Publish.Start" -> return
                        code.contains("Publish.BadName") || code.contains("Failed") ||
                            code.contains("Rejected") || (info?.get("level") == "error") ->
                            throw RtmpException(RtmpErrorKind.SERVER_REJECTED, "服务器拒绝推流：${describe(values)}")
                        else -> log("onStatus $code")
                    }
                }
                "_error" -> throw RtmpException(RtmpErrorKind.SERVER_REJECTED, "服务器拒绝推流：${describe(values)}")
            }
        }
    }

    private fun describe(values: List<Any?>): String {
        val info = values.getOrNull(3) as? Map<*, *>
        val code = info?.get("code") ?: ""
        val desc = info?.get("description") ?: ""
        return "$code $desc".trim().ifEmpty { "无详细信息" }
    }

    private fun awaitResult(id: Int): Pair<String, List<Any?>> {
        while (true) {
            val (name, values) = readCommandMessage()
            if ((name == "_result" || name == "_error") && (values.getOrNull(1) as? Double)?.toInt() == id) {
                return name to values
            }
        }
    }

    /** 建连阶段同步读取，直到拿到一个 AMF 命令。 */
    private fun readCommandMessage(): Pair<String, List<Any?>> {
        try {
            while (true) {
                val m = readMessage()
                val cmd = handleControl(m) ?: continue
                return cmd
            }
        } catch (e: RtmpException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw RtmpException(RtmpErrorKind.TIMEOUT, "等待服务器响应超时", e)
        } catch (e: EOFException) {
            throw RtmpException(RtmpErrorKind.SERVER_REJECTED, "服务器关闭了连接（可能是应用名或 Stream Key 无效）", e)
        } catch (e: IOException) {
            throw RtmpException(RtmpErrorKind.NETWORK, "读取服务器响应失败：${e.message}", e)
        }
    }

    /** 处理协议控制消息；如果是 AMF 命令则返回 (name, values)。 */
    private fun handleControl(m: Message): Pair<String, List<Any?>>? {
        val p = m.payload
        when (m.typeId) {
            1 -> inChunkSize = be32(p, 0) and 0x7FFFFFFF
            3 -> {} // ack
            4 -> {
                val event = be16(p, 0)
                if (event == 6 && p.size >= 6) { // ping request → pong
                    val ts = p.copyOfRange(2, 6)
                    sendControl(4, byteArrayOf(0, 7) + ts)
                }
            }
            5 -> windowAckSize = (be32(p, 0).toLong() and 0xFFFFFFFFL)
            6 -> {}
            20, 17 -> {
                val off = if (m.typeId == 17) 1 else 0
                val values = Amf0.decodeAll(p, off)
                val name = values.firstOrNull() as? String ?: return null
                return name to values
            }
        }
        return null
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val m = readMessage()
                val cmd = handleControl(m) ?: continue
                if (cmd.first == "onStatus") {
                    val info = cmd.second.getOrNull(3) as? Map<*, *>
                    if (info?.get("level") == "error") {
                        asyncError = RtmpException(RtmpErrorKind.SERVER_REJECTED, "服务器中止推流：${describe(cmd.second)}")
                        try { socket?.close() } catch (_: Exception) {}
                        return
                    }
                }
            }
        } catch (e: Exception) {
            if (!closed) {
                asyncError = RtmpException(RtmpErrorKind.NETWORK, "连接中断：${e.message ?: e.javaClass.simpleName}", e)
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    // ---------------- chunk read ----------------

    private fun readMessage(): Message {
        while (true) {
            val b0 = readByte()
            val fmt = b0 ushr 6
            var csid = b0 and 0x3F
            if (csid == 0) csid = 64 + readByte()
            else if (csid == 1) { val a = readByte(); val b = readByte(); csid = 64 + a + b * 256 }
            val h = inHeaders.getOrPut(csid) { InHeader() }
            when (fmt) {
                0 -> {
                    var ts = readU24().toLong()
                    h.length = readU24(); h.typeId = readByte()
                    h.streamId = readByte() or (readByte() shl 8) or (readByte() shl 16) or (readByte() shl 24)
                    h.extended = ts == 0xFFFFFFL
                    if (h.extended) ts = readU32()
                    h.timestamp = ts; h.timestampDelta = 0
                }
                1 -> {
                    var d = readU24().toLong()
                    h.length = readU24(); h.typeId = readByte()
                    h.extended = d == 0xFFFFFFL
                    if (h.extended) d = readU32()
                    h.timestampDelta = d
                    if (h.buffer == null) h.timestamp += d
                }
                2 -> {
                    var d = readU24().toLong()
                    h.extended = d == 0xFFFFFFL
                    if (h.extended) d = readU32()
                    h.timestampDelta = d
                    if (h.buffer == null) h.timestamp += d
                }
                3 -> {
                    if (h.extended) readU32()
                    if (h.buffer == null) h.timestamp += h.timestampDelta
                }
            }
            if (h.buffer == null) { h.buffer = ByteArray(h.length); h.filled = 0 }
            val buf = h.buffer!!
            val n = minOf(inChunkSize, h.length - h.filled)
            readInto(buf, h.filled, n)
            h.filled += n
            maybeAck()
            if (h.filled >= h.length) {
                h.buffer = null
                return Message(h.typeId, h.streamId, h.timestamp, buf)
            }
        }
    }

    private fun maybeAck() {
        if (windowAckSize > 0 && bytesRead - lastAck >= windowAckSize / 2) {
            lastAck = bytesRead
            val v = bytesRead.toInt()
            sendControl(3, byteArrayOf((v ushr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
        }
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("连接被服务器关闭")
        bytesRead++
        return b
    }

    private fun readInto(b: ByteArray, off: Int, n: Int) {
        var o = off
        while (o < off + n) {
            val r = input.read(b, o, off + n - o)
            if (r < 0) throw EOFException("连接被服务器关闭")
            o += r
        }
        bytesRead += n
    }

    private fun readU24() = (readByte() shl 16) or (readByte() shl 8) or readByte()
    private fun readU32(): Long = (readU24().toLong() shl 8) or readByte().toLong()
    private fun be32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    private fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    // ---------------- chunk write ----------------

    private fun setChunkSize(size: Int) {
        sendControl(1, byteArrayOf((size ushr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()))
        outChunkSize = size
    }

    private fun sendControl(typeId: Int, payload: ByteArray) {
        writeMessage(2, typeId, 0, 0, payload)
    }

    private fun sendCommand(csid: Int, msgStream: Int, name: String, txnId: Int, obj: Map<String, Any?>?, vararg args: Any?) {
        val payload = Amf0.encode(name, txnId, obj, *args)
        writeMessage(csid, 20, msgStream, 0, payload)
    }

    fun sendMetadata(meta: Map<String, Any?>) {
        val payload = Amf0.encode("@setDataFrame", "onMetaData", Amf0.EcmaArray(meta))
        writeMessage(4, 18, streamId, 0, payload)
    }

    /** 发送一个 FLV tag body。timestampMs 为相对本连接起点的毫秒。 */
    fun writeMedia(type: MediaPacket.Type, timestampMs: Long, body: ByteArray) {
        val csid = if (type == MediaPacket.Type.AUDIO) 4 else 6
        val typeId = if (type == MediaPacket.Type.AUDIO) 8 else 9
        writeMessage(csid, typeId, streamId, timestampMs, body)
    }

    private fun writeMessage(csid: Int, typeId: Int, msgStream: Int, timestamp: Long, payload: ByteArray) {
        synchronized(writeLock) {
            if (closed && typeId != 20) throw RtmpException(RtmpErrorKind.NETWORK, "连接已关闭")
            writeStartedAt = System.currentTimeMillis()
            try {
                val ts = timestamp and 0xFFFFFFFFL
                val extended = ts >= 0xFFFFFF
                val header = ByteArray(12 + if (extended) 4 else 0)
                header[0] = (csid and 0x3F).toByte() // fmt 0
                val t24 = if (extended) 0xFFFFFF else ts.toInt()
                header[1] = (t24 shr 16).toByte(); header[2] = (t24 shr 8).toByte(); header[3] = t24.toByte()
                val len = payload.size
                header[4] = (len shr 16).toByte(); header[5] = (len shr 8).toByte(); header[6] = len.toByte()
                header[7] = typeId.toByte()
                header[8] = msgStream.toByte(); header[9] = (msgStream shr 8).toByte()
                header[10] = (msgStream shr 16).toByte(); header[11] = (msgStream shr 24).toByte()
                if (extended) {
                    val v = ts.toInt()
                    header[12] = (v ushr 24).toByte(); header[13] = (v shr 16).toByte()
                    header[14] = (v shr 8).toByte(); header[15] = v.toByte()
                }
                output.write(header)
                var off = 0
                var written = header.size.toLong()
                while (off < len) {
                    if (off > 0) {
                        output.write(0xC0 or (csid and 0x3F)) // fmt 3
                        written++
                        if (extended) { output.write(header, 12, 4); written += 4 }
                    }
                    val n = minOf(outChunkSize, len - off)
                    output.write(payload, off, n)
                    off += n
                    written += n
                }
                output.flush()
                bytesWritten += written
            } catch (e: RtmpException) {
                throw e
            } catch (e: IOException) {
                throw asyncError ?: RtmpException(RtmpErrorKind.NETWORK, "发送失败：${e.message}", e)
            } finally {
                writeStartedAt = 0
            }
        }
    }
}
