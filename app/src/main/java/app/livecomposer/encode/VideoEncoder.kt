package app.livecomposer.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import app.livecomposer.rtmp.FlvMux
import app.livecomposer.rtmp.MediaPacket

/** H.264 硬件编码（Surface 输入）。输出转换为 FLV tag body 交给推流队列。 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val keyFrameIntervalSec: Int,
    private val onConfig: (ByteArray) -> Unit,
    private val onPacket: (MediaPacket) -> Unit,
    private val onError: (String) -> Unit,
) {
    private lateinit var codec: MediaCodec
    lateinit var inputSurface: Surface; private set
    @Volatile private var running = false
    private var drainThread: Thread? = null
    var codecName: String = ""; private set

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) // RTMP 发送不处理 B 帧重排
            // Surface 输入时若画面静止，最多 1/fps 秒后重复上一帧，保证帧率稳定
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
        }
        val name = pickEncoder(format)
        codec = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codecName = codec.name
        // 优先 High Profile，不支持时回退到默认
        val high = MediaFormat(format).apply {
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        }
        try {
            codec.configure(high, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "High profile not accepted, fallback", e)
            codec.reset()
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = codec.createInputSurface()
        codec.start()
        running = true
        drainThread = Thread({ drain() }, "video-encoder").also { it.start() }
    }

    /** 优先选择硬件 H.264 编码器。 */
    private fun pickEncoder(format: MediaFormat): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }
        val hw = list.filter { it.isHardwareAccelerated && !it.isSoftwareOnly }
        val ok = hw.firstOrNull { info ->
            val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            caps.videoCapabilities.isSizeSupported(width, height)
        }
        return ok?.name ?: list.firstOrNull()?.name
    }

    fun requestKeyFrame() {
        if (!running) return
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            Log.w(TAG, "request key frame", e)
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        val sps = f.getByteBuffer("csd-0")
                        val pps = f.getByteBuffer("csd-1")
                        if (sps != null && pps != null) {
                            val s = FlvMux.splitAnnexB(ByteArray(sps.remaining()).also { sps.get(it) }).firstOrNull()
                            val p = FlvMux.splitAnnexB(ByteArray(pps.remaining()).also { pps.get(it) }).firstOrNull()
                            if (s != null && p != null) onConfig(FlvMux.avcSequenceHeader(s, p))
                        }
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val data = ByteArray(info.size).also { buf.get(it) }
                            val nals = FlvMux.splitAnnexB(data)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                val sps = nals.firstOrNull { FlvMux.nalType(it) == 7 }
                                val pps = nals.firstOrNull { FlvMux.nalType(it) == 8 }
                                if (sps != null && pps != null) onConfig(FlvMux.avcSequenceHeader(sps, pps))
                            } else {
                                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                // 部分编码器在关键帧前内嵌 SPS/PPS：同步更新序列头
                                if (key) {
                                    val sps = nals.firstOrNull { FlvMux.nalType(it) == 7 }
                                    val pps = nals.firstOrNull { FlvMux.nalType(it) == 8 }
                                    if (sps != null && pps != null) onConfig(FlvMux.avcSequenceHeader(sps, pps))
                                }
                                FlvMux.avcFrame(nals, key)?.let {
                                    onPacket(MediaPacket(MediaPacket.Type.VIDEO, info.presentationTimeUs, it, key, false))
                                }
                            }
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "drain", e)
                onError("视频编码失败：${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        drainThread?.join(1000)
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { inputSurface.release() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "VideoEncoder"
    }
}
