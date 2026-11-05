import app.livecomposer.rtmp.FlvMux
import app.livecomposer.rtmp.MediaPacket
import app.livecomposer.rtmp.RtmpException
import app.livecomposer.rtmp.RtmpPublisher
import app.livecomposer.rtmp.RtmpUrl
import java.io.File

/**
 * 端到端推流测试：读取 ffmpeg 生成的 Annex-B H.264（带 AUD）与 ADTS AAC，
 * 按真实时间节奏经 RtmpPublisher 推到服务器。
 * 参数：<h264> <aac 或 -（只推画面）> <rtmpUrl> [fps] [秒数]
 */
fun main(args: Array<String>) {
    val h264 = File(args[0]).readBytes()
    val videoOnly = args[1] == "-"
    val aac = if (videoOnly) ByteArray(0) else File(args[1]).readBytes()
    val url = RtmpUrl.parse(args[2])
    val fps = args.getOrNull(3)?.toInt() ?: 30
    val seconds = args.getOrNull(4)?.toInt() ?: 10

    // ---- H.264 按 AUD 切分访问单元 ----
    val nals = FlvMux.splitAnnexB(h264)
    val frames = ArrayList<List<ByteArray>>()
    var cur = ArrayList<ByteArray>()
    var sps: ByteArray? = null
    var pps: ByteArray? = null
    for (n in nals) {
        when (FlvMux.nalType(n)) {
            9 -> { if (cur.isNotEmpty()) frames.add(cur); cur = ArrayList() }
            7 -> if (sps == null) sps = n
            8 -> if (pps == null) pps = n
        }
        cur.add(n)
    }
    if (cur.isNotEmpty()) frames.add(cur)

    // ---- ADTS 解析 ----
    val aacFrames = ArrayList<ByteArray>()
    var i = 0
    var sampleRate = 44100
    var channels = 1
    val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    while (i + 7 <= aac.size) {
        val protAbsent = aac[i + 1].toInt() and 1
        val sfi = (aac[i + 2].toInt() shr 2) and 0xF
        channels = ((aac[i + 2].toInt() and 1) shl 2) or ((aac[i + 3].toInt() shr 6) and 3)
        sampleRate = rates[sfi]
        val len = ((aac[i + 3].toInt() and 3) shl 11) or ((aac[i + 4].toInt() and 0xFF) shl 3) or ((aac[i + 5].toInt() and 0xFF) shr 5)
        val hdr = if (protAbsent == 1) 7 else 9
        aacFrames.add(aac.copyOfRange(i + hdr, i + len))
        i += len
    }
    println("video frames=${frames.size} audio frames=${aacFrames.size} sr=$sampleRate ch=$channels -> ${url.masked()}")

    val lock = Object()
    var lastState: RtmpPublisher.State? = null
    val pub = RtmpPublisher(object : RtmpPublisher.Listener {
        override fun onStateChanged(state: RtmpPublisher.State, error: RtmpException?, retryInMs: Long) {
            println("state=${state.label} ${error?.let { "[${it.kind.label}] ${it.message}" } ?: ""} ${if (retryInMs > 0) "retry=${retryInMs}ms" else ""}")
            synchronized(lock) { lastState = state; lock.notifyAll() }
        }
        override fun onKeyFrameRequested() {}
    }, sslFactory = sslFactory(), log = { println("log: $it") })
    pub.videoConfig = FlvMux.avcSequenceHeader(sps!!, pps!!)
    if (!videoOnly) pub.audioConfig = FlvMux.aacSequenceHeader(FlvMux.aacLcConfig(sampleRate, channels))
    pub.start(url, if (videoOnly) RtmpPublisher.StreamInfo(1280, 720, fps, 2_000_000, 0, 0, 0)
        else RtmpPublisher.StreamInfo(1280, 720, fps, 2_000_000, sampleRate, channels, 128_000))

    val start = System.nanoTime()
    var vi = 0
    var ai = 0
    val totalUs = seconds * 1_000_000L
    while (true) {
        val nowUs = (System.nanoTime() - start) / 1000
        if (nowUs > totalUs || pub.state == RtmpPublisher.State.FAILED) break
        while (vi * 1_000_000L / fps <= nowUs) {
            val f = frames[vi % frames.size]
            val key = f.any { FlvMux.nalType(it) == 5 }
            FlvMux.avcFrame(f, key)?.let { pub.enqueue(MediaPacket(MediaPacket.Type.VIDEO, start / 1000 + vi * 1_000_000L / fps, it, key, false)) }
            vi++
        }
        while (!videoOnly && ai * 1024L * 1_000_000L / sampleRate <= nowUs) {
            val a = aacFrames[ai % aacFrames.size]
            pub.enqueue(MediaPacket(MediaPacket.Type.AUDIO, start / 1000 + ai * 1024L * 1_000_000L / sampleRate, FlvMux.aacFrame(a), false, false))
            ai++
        }
        Thread.sleep(5)
    }
    val st = pub.state
    println("RESULT state=${st.label} sentVideo=${pub.sentVideoFrames} dropped=${pub.droppedVideoFrames} bytes=${pub.bytesSent} reconnects=${pub.reconnectCount}")
    pub.stop()
}

/** 测试用：环境变量 E2E_TRUST 指向 PKCS12 信任库时，用它校验 RTMPS 自签证书。 */
private fun sslFactory(): javax.net.ssl.SSLSocketFactory {
    val path = System.getenv("E2E_TRUST") ?: return javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
    val ks = java.security.KeyStore.getInstance("PKCS12")
    File(path).inputStream().use { ks.load(it, "changeit".toCharArray()) }
    val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(ks)
    val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
    ctx.init(null, tmf.trustManagers, null)
    return ctx.socketFactory
}
