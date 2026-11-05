package app.livecomposer.rtmp

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.withLock

/**
 * 推流会话：负责连接、发送队列、丢帧策略、断线自动重连（1、2、5、10 秒递增，最大 10 秒）。
 * 纯 Kotlin，不依赖 Android。
 */
class RtmpPublisher(
    private val listener: Listener,
    private val sslFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val log: (String) -> Unit = {},
) {
    enum class State(val label: String) {
        IDLE("未开始"), CONNECTING("连接中"), LIVE("直播中"), RECONNECTING("重连中"), FAILED("失败"), STOPPED("已停止")
    }

    interface Listener {
        /** retryInMs 仅在 RECONNECTING 时有效。 */
        fun onStateChanged(state: State, error: RtmpException?, retryInMs: Long)
        /** 新连接建立或丢帧后需要编码器尽快产出关键帧。 */
        fun onKeyFrameRequested()
    }

    data class StreamInfo(
        val width: Int,
        val height: Int,
        val fps: Int,
        val videoBitrate: Int,
        val audioSampleRate: Int,
        val audioChannels: Int,
        val audioBitrate: Int,
    )

    companion object {
        val RETRY_DELAYS_MS = longArrayOf(1000, 2000, 5000, 10000)
        const val MAX_LATENCY_MS = 2000L
        const val WRITE_STALL_MS = 8000L
    }

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val queue = ArrayDeque<MediaPacket>()
    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var needKeyFrame = true
    private var thread: Thread? = null
    @Volatile private var conn: RtmpConnection? = null

    @Volatile var state: State = State.IDLE; private set
    @Volatile var videoConfig: ByteArray? = null
    @Volatile var audioConfig: ByteArray? = null

    // ---- 统计 ----
    @Volatile private var bytesFromClosedConnections = 0L
    val bytesSent: Long get() = bytesFromClosedConnections + (conn?.bytesWritten ?: 0L)
    @Volatile var droppedVideoFrames = 0L; private set
    @Volatile var sentVideoFrames = 0L; private set
    @Volatile var reconnectCount = 0; private set

    fun start(url: RtmpUrl, info: StreamInfo) {
        check(!running) { "already running" }
        running = true
        droppedVideoFrames = 0; sentVideoFrames = 0; reconnectCount = 0; bytesFromClosedConnections = 0
        thread = Thread({ runLoop(url, info) }, "rtmp-sender").also { it.start() }
    }

    @Volatile private var retryAttempt = 0

    /** 系统网络恢复：如果正在等待重连，立即重连并重置退避间隔。 */
    fun reconnectNow() {
        if (!running || state != State.RECONNECTING) return
        retryAttempt = 0
        thread?.interrupt()
    }

    /** 系统报告当前网络已断开：主动断开连接，尽快进入重连，而不是等 TCP 超时。 */
    fun dropConnection() {
        if (!running || state != State.LIVE) return
        log("系统网络断开，主动进入重连")
        conn?.close()
    }

    fun stop() {
        running = false
        lock.withLock { queue.clear(); notEmpty.signalAll() }
        conn?.close()
        thread?.interrupt()
        thread?.join(3000)
        thread = null
        setState(State.STOPPED, null, 0)
    }

    /** 由编码器线程调用。未连接时直接丢弃（不排队），避免重连后发送大量过期数据。 */
    fun enqueue(p: MediaPacket) {
        if (!running || !connected) return
        lock.withLock {
            queue.addLast(p)
            val first = queue.peekFirst()
            if (first != null && p.ptsUs - first.ptsUs > MAX_LATENCY_MS * 1000) {
                // 网络发送跟不上：丢弃排队中的视频帧，等待下一个关键帧
                val it = queue.iterator()
                var dropped = 0
                while (it.hasNext()) {
                    val q = it.next()
                    if (q.type == MediaPacket.Type.VIDEO) { it.remove(); dropped++ }
                }
                // 音频积压过多也只保留最近 MAX_LATENCY_MS
                while (queue.size > 0 && p.ptsUs - queue.peekFirst()!!.ptsUs > MAX_LATENCY_MS * 1000) queue.removeFirst()
                droppedVideoFrames += dropped
                if (dropped > 0) {
                    needKeyFrame = true
                    log("拥塞丢弃 $dropped 个视频帧")
                    listener.onKeyFrameRequested()
                }
            }
            notEmpty.signal()
        }
    }

    private fun setState(s: State, e: RtmpException?, retry: Long) {
        state = s
        listener.onStateChanged(s, e, retry)
    }

    private fun runLoop(url: RtmpUrl, info: StreamInfo) {
        retryAttempt = 0
        while (running) {
            Thread.interrupted() // 清除 reconnectNow() 留下的中断标记
            setState(if (retryAttempt == 0 && reconnectCount == 0) State.CONNECTING else State.RECONNECTING, null, 0)
            val c = RtmpConnection(url, sslFactory = sslFactory, log = log)
            try {
                c.connectAndPublish()
                conn = c
                retryAttempt = 0
                lock.withLock { queue.clear() }
                needKeyFrame = true
                connected = true
                setState(State.LIVE, null, 0)
                listener.onKeyFrameRequested()
                sendLoop(c, info)
            } catch (e: Exception) {
                connected = false
                val err = e as? RtmpException ?: RtmpException(RtmpErrorKind.NETWORK, e.message ?: "未知错误", e)
                c.close()
                if (conn === c) { bytesFromClosedConnections += c.bytesWritten; conn = null }
                if (!running) break
                // 首次连接遇到不可重试错误：直接失败；直播中断后的重连对所有网络类错误都持续重试
                val fatal = err.kind == RtmpErrorKind.BAD_URL || err.kind == RtmpErrorKind.TLS ||
                    (err.kind == RtmpErrorKind.SERVER_REJECTED && reconnectCount == 0 && retryAttempt == 0) ||
                    err.kind == RtmpErrorKind.ENCODER
                if (fatal) {
                    running = false
                    setState(State.FAILED, err, 0)
                    return
                }
                val delay = RETRY_DELAYS_MS[minOf(retryAttempt, RETRY_DELAYS_MS.size - 1)]
                retryAttempt++
                reconnectCount++
                log("推流中断（${err.kind.label}）：${err.message}，${delay}ms 后重连")
                setState(State.RECONNECTING, err, delay)
                try { Thread.sleep(delay) } catch (_: InterruptedException) { }
            }
        }
    }

    private fun sendLoop(c: RtmpConnection, info: StreamInfo) {
        val meta = linkedMapOf<String, Any>(
            "width" to info.width, "height" to info.height,
            "videocodecid" to 7, "videodatarate" to info.videoBitrate / 1000.0,
            "framerate" to info.fps,
        )
        if (info.audioSampleRate > 0) { // 0 = 只推画面（第二路关闭声音时）
            meta["audiocodecid"] = 10; meta["audiodatarate"] = info.audioBitrate / 1000.0
            meta["audiosamplerate"] = info.audioSampleRate; meta["audiosamplesize"] = 16
            meta["stereo"] = info.audioChannels == 2
        }
        meta["encoder"] = "LiveComposer"
        c.sendMetadata(meta)
        var baseUs = -1L
        var lastVideoTs = -1L
        var lastAudioTs = -1L
        var videoConfigSent = false
        var audioConfigSent = false
        while (running) {
            c.asyncError?.let { throw it }
            val ws = c.writeStartedAt
            if (ws != 0L && System.currentTimeMillis() - ws > WRITE_STALL_MS) {
                throw RtmpException(RtmpErrorKind.NETWORK, "发送阻塞超时，网络可能已断开")
            }
            val p = lock.withLock {
                if (queue.isEmpty()) {
                    try { notEmpty.awaitNanos(200_000_000L) } catch (_: InterruptedException) { }
                }
                queue.pollFirst()
            } ?: continue

            if (p.type == MediaPacket.Type.VIDEO) {
                if (needKeyFrame && !p.isKeyFrame) continue
                val cfg = videoConfig ?: continue
                needKeyFrame = false
                if (baseUs < 0) baseUs = p.ptsUs
                val ts = maxOf(0L, (p.ptsUs - baseUs) / 1000, lastVideoTs)
                if (!videoConfigSent) { c.writeMedia(MediaPacket.Type.VIDEO, ts, cfg); videoConfigSent = true }
                c.writeMedia(MediaPacket.Type.VIDEO, ts, p.body)
                lastVideoTs = ts
                sentVideoFrames++
            } else {
                val cfg = audioConfig ?: continue
                if (baseUs < 0) baseUs = p.ptsUs
                if (p.ptsUs < baseUs) continue
                val ts = maxOf((p.ptsUs - baseUs) / 1000, lastAudioTs)
                if (!audioConfigSent) { c.writeMedia(MediaPacket.Type.AUDIO, ts, cfg); audioConfigSent = true }
                c.writeMedia(MediaPacket.Type.AUDIO, ts, p.body)
                lastAudioTs = ts
            }
        }
    }
}
