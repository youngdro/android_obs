package app.livecomposer.source

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import app.livecomposer.mix.AudioMixer
import java.nio.ByteBuffer

/**
 * 媒体图层来源：拉流地址（HLS / RTMP / RTSP / HTTP-FLV / HTTP MP4）或本地视频文件，基于 Media3 ExoPlayer。
 *
 * - 画面：解码器直接输出到合成器提供的 Surface（SurfaceTexture → OES 纹理），和网页图层走同一条路。
 * - 声音：音频渲染链里插一个 TeeAudioProcessor，把解码后的 PCM 交给 [AudioMixer] 混进推流；
 *   本机扬声器输出音量设为 0，避免外放又被麦克风收进去形成回声。
 * - 拉流断线 / 出错按 1、2、5、10 秒递增自动重连；网络视频（http(s) 点播）网络出错同样重连，并从断点继续播放。
 * - 视频文件（本地或网络）可设置循环或不循环，播放中可随时切换。
 *
 * 所有方法在主线程调用。
 */
@OptIn(UnstableApi::class)
class PlayerSource(
    private val context: Context,
    val id: String,
    private val uri: String,
    private val live: Boolean,
    private var loop: Boolean,
    private val mixer: AudioMixer,
    volume: Float,
    private val listener: Listener,
) {
    enum class State(val label: String) {
        IDLE("未开始"), CONNECTING("连接中"), BUFFERING("缓冲中"), PLAYING("播放中"),
        RECONNECTING("重连中"), ENDED("已播完"), ERROR("播放失败"),
    }

    interface Listener {
        fun onStateChanged(state: State, message: String?)
        /** 画面尺寸（已按旋转和像素宽高比换算成显示尺寸）。 */
        fun onVideoSize(width: Int, height: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var released = false
    private var everPlayed = false
    private var retry = 0
    /** 网络视频重连时从这里继续（毫秒）。 */
    private var resumeAt = 0L
    private val remote = !live && (uri.startsWith("http://", true) || uri.startsWith("https://", true))
    var state = State.IDLE; private set
    var message: String? = null; private set

    init { mixer.addSource(id, volume) }

    fun attach(s: Surface) {
        if (released) return
        surface = s
        start()
    }

    fun setVolume(v: Float) = mixer.setVolume(id, v)

    fun pause() { player?.pause() }

    /** 切换循环 / 不循环（只对视频文件有效）。已播完时打开循环会从头重新播放。 */
    fun setLoop(value: Boolean) {
        if (live) return
        loop = value
        val p = player ?: return
        p.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (value && p.playbackState == Player.STATE_ENDED) { p.seekTo(0); p.play() }
    }

    fun resume() {
        val p = player ?: return
        if (live) p.seekToDefaultPosition()
        p.play()
    }

    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        player?.release(); player = null
        mixer.removeSource(id)
    }

    private fun setState(s: State, msg: String? = null) {
        state = s; message = msg
        listener.onStateChanged(s, msg)
    }

    private fun start() {
        player?.let { old -> if (remote && everPlayed) resumeAt = old.currentPosition }
        player?.release()
        val p = build()
        player = p
        p.setVideoSurface(surface)
        p.volume = 0f // 本机静音，声音只进推流
        p.repeatMode = if (!live && loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> setState(if (everPlayed) State.BUFFERING else State.CONNECTING)
                    Player.STATE_READY -> { everPlayed = true; retry = 0; setState(State.PLAYING) }
                    Player.STATE_ENDED -> if (live) scheduleReconnect("直播流已结束") else setState(State.ENDED)
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = describe(error)
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition(); p.prepare(); return
                }
                if ((live || remote) && error.errorCode !in FATAL) scheduleReconnect(msg) else setState(State.ERROR, msg)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                listener.onVideoSize((videoSize.width * videoSize.pixelWidthHeightRatio).toInt(), videoSize.height)
            }
        })
        setState(State.CONNECTING)
        if (resumeAt > 0) p.setMediaItem(MediaItem.fromUri(Uri.parse(uri)), resumeAt)
        else p.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
        p.playWhenReady = true
        p.prepare()
    }

    private fun scheduleReconnect(reason: String) {
        if (released) return
        val delay = RETRY_DELAYS_MS[minOf(retry, RETRY_DELAYS_MS.size - 1)]
        retry++
        setState(State.RECONNECTING, "$reason，${delay / 1000} 秒后重连")
        main.postDelayed({ if (!released) start() }, delay)
    }

    private fun build(): ExoPlayer {
        val sink = object : TeeAudioProcessor.AudioBufferSink {
            override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) =
                mixer.configure(id, sampleRateHz, channelCount, encoding)

            override fun handleBuffer(buffer: ByteBuffer) = mixer.write(id, buffer)
        }
        val audioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(TeeAudioProcessor(sink)))
            .build()
        val renderers = RenderersFactory { handler, videoListener, audioListener, _, _ ->
            arrayOf<Renderer>(
                MediaCodecVideoRenderer(context, MediaCodecSelector.DEFAULT, 5_000, handler, videoListener, 50),
                MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, audioListener, audioSink),
            )
        }
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000).setReadTimeoutMs(8_000)
            .setUserAgent("LiveComposer")
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
        val load = if (live) {
            // 直播拉流：少缓冲，降低延迟
            DefaultLoadControl.Builder().setBufferDurationsMs(1_500, 6_000, 500, 1_000).build()
        } else DefaultLoadControl()
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(sources)
            .setLoadControl(load)
            .build()
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "无法连接拉流地址"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "服务器返回错误状态"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件或地址不存在"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "没有读取权限"
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "不允许 HTTP 明文地址"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "格式不支持或数据损坏"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "本机无法解码该视频格式"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败（可能同时解码的视频过多）"
        else -> "播放出错：${e.errorCodeName}"
    }

    companion object {
        private val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 5_000, 10_000)
        /** 这些错误重连也不会好，直接报错。 */
        private val FATAL = setOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )
    }
}
