package app.livecomposer.stream

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.livecomposer.encode.AudioPipeline
import app.livecomposer.encode.VideoEncoder
import app.livecomposer.gl.Compositor
import app.livecomposer.rtmp.RtmpErrorKind
import app.livecomposer.rtmp.RtmpException
import app.livecomposer.rtmp.RtmpPublisher
import app.livecomposer.rtmp.RtmpUrl

/**
 * 串联「合成器 → 视频编码 / 音频编码 → RTMP」并统计运行状态。所有回调在主线程。
 *
 * - [Kind.MAIN]：主推流，合成后的画面 + 混音；
 * - [Kind.CAMERA]：第二路，纯摄像头画面 + 可选的麦克风原声。与主推流各自独立启停、独立重连。
 */
class StreamController(
    private val compositor: Compositor,
    private val audio: AudioPipeline,
    private val listener: Listener,
    private val kind: Kind = Kind.MAIN,
) {
    enum class Kind { MAIN, CAMERA }

    /** 本路是否带声音（主推流总是带；第二路可选）。 */
    private var withAudio = true
    data class Stats(
        val state: RtmpPublisher.State,
        val durationSec: Long,
        val outputFps: Float,
        val bitrateKbps: Float,
        val droppedFrames: Long,
        val renderLate: Long,
        val reconnects: Int,
        val message: String?,
    )

    interface Listener {
        fun onStats(stats: Stats)
        fun onStreamEnded(error: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private var publisher: RtmpPublisher? = null
    private var video: VideoEncoder? = null
    private var startedAt = 0L
    private var lastBytes = 0L
    private var lastFrames = 0L
    private var lastTick = 0L
    private var message: String? = null
    val isActive: Boolean get() = publisher != null

    fun start(url: RtmpUrl, width: Int, height: Int, fps: Int, bitrate: Int, gopSec: Int, withAudio: Boolean = true) {
        if (publisher != null) return
        message = null
        this.withAudio = kind == Kind.MAIN || withAudio
        val pub = RtmpPublisher(object : RtmpPublisher.Listener {
            override fun onStateChanged(state: RtmpPublisher.State, error: RtmpException?, retryInMs: Long) {
                main.post {
                    message = when {
                        error != null && state == RtmpPublisher.State.RECONNECTING ->
                            "${error.kind.label}：${error.message}，${retryInMs / 1000} 秒后重连"
                        error != null -> "${error.kind.label}：${error.message}"
                        state == RtmpPublisher.State.LIVE -> null
                        else -> message
                    }
                    if (state == RtmpPublisher.State.FAILED) stop(message)
                }
            }

            override fun onKeyFrameRequested() { video?.requestKeyFrame() }
        }, log = { Log.i(TAG, it) })

        val v = VideoEncoder(width, height, fps, bitrate, gopSec,
            onConfig = { pub.videoConfig = it },
            onPacket = { pub.enqueue(it) },
            onError = { msg -> main.post { stop("${RtmpErrorKind.ENCODER.label}：$msg") } })
        try {
            v.start()
        } catch (e: Exception) {
            listener.onStreamEnded("${RtmpErrorKind.ENCODER.label}：无法启动 H.264 编码器（${e.message}）")
            return
        }
        setVideoTarget(v.inputSurface, width, height)
        try {
            if (this.withAudio) attachAudio(onConfig = { pub.audioConfig = it }, onPacket = { pub.enqueue(it) })
        } catch (e: Exception) {
            setVideoTarget(null, 0, 0); v.stop()
            listener.onStreamEnded("${RtmpErrorKind.ENCODER.label}：无法启动 AAC 编码器（${e.message}）")
            return
        }
        video = v
        publisher = pub
        pub.start(url, if (this.withAudio) RtmpPublisher.StreamInfo(width, height, fps, bitrate, audio.sampleRate, audio.channels, audio.bitrate)
            else RtmpPublisher.StreamInfo(width, height, fps, bitrate, 0, 0, 0))
        startedAt = SystemClock.elapsedRealtime()
        lastTick = startedAt; lastBytes = 0; lastFrames = 0
        Log.i(TAG, "start[$kind] ${url.masked()} ${width}x$height@$fps ${bitrate / 1000}kbps encoder=${v.codecName}")
        main.post(statsTick)
    }

    private val statsTick = object : Runnable {
        override fun run() {
            val pub = publisher ?: return
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastTick).coerceAtLeast(1) / 1000f
            val bytes = pub.bytesSent
            val frames = if (kind == Kind.MAIN) compositor.encodedFrames else compositor.cameraEncodedFrames
            val stats = Stats(
                state = pub.state,
                durationSec = (now - startedAt) / 1000,
                outputFps = (frames - lastFrames).coerceAtLeast(0) / dt,
                bitrateKbps = (bytes - lastBytes).coerceAtLeast(0) * 8 / 1000f / dt,
                droppedFrames = pub.droppedVideoFrames,
                renderLate = compositor.lateFrames,
                reconnects = pub.reconnectCount,
                message = message,
            )
            lastTick = now; lastBytes = bytes; lastFrames = frames
            listener.onStats(stats)
            main.postDelayed(this, 1000)
        }
    }

    /** 系统网络恢复 / 断开（由 Activity 的网络监听转发）。 */
    fun onNetworkAvailable() { publisher?.reconnectNow() }
    fun onNetworkLost() { publisher?.dropConnection() }

    fun stop(error: String? = null) {
        val pub = publisher ?: return
        publisher = null
        main.removeCallbacks(statsTick)
        // 停止顺序：先断开合成器输出，再停编码器，最后关闭连接
        setVideoTarget(null, 0, 0)
        if (withAudio) detachAudio()
        video?.stop(); video = null
        Thread { pub.stop() }.start()
        listener.onStreamEnded(error)
    }

    private fun setVideoTarget(s: android.view.Surface?, w: Int, h: Int) =
        if (kind == Kind.MAIN) compositor.setEncoderSurface(s) else compositor.setCameraEncoderSurface(s, w, h)

    private fun attachAudio(onConfig: (ByteArray) -> Unit, onPacket: (app.livecomposer.rtmp.MediaPacket) -> Unit) =
        if (kind == Kind.MAIN) audio.attachEncoder(onConfig, onPacket) else audio.attachMicEncoder(onConfig, onPacket)

    private fun detachAudio() = if (kind == Kind.MAIN) audio.detachEncoder() else audio.detachMicEncoder()

    companion object {
        private const val TAG = "StreamController"
    }
}
