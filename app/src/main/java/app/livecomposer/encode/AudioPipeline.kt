package app.livecomposer.encode

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import app.livecomposer.rtmp.FlvMux
import app.livecomposer.rtmp.MediaPacket
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 麦克风采集 + AAC 编码。
 * - 采集在预览阶段就运行（用于电平显示），编码器只在推流时挂上；
 * - 静音时继续采集但送入全 0 PCM，接收端音频同步静音、时间轴不断；
 * - 时间戳基于 AudioRecord.getTimestamp() 与 System.nanoTime() 对齐（与视频同一时钟），避免长时间累积漂移。
 */
class AudioPipeline(
    val sampleRate: Int = 48_000,
    val channels: Int = 1,
    val bitrate: Int = 128_000,
    private val onLevel: (Float) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var muted = false
    /** 音画同步测试：替换麦克风输入，在每个整秒（MONOTONIC）开始的 50ms 内输出 1kHz 提示音，其余静音。 */
    @Volatile var syncTest = false

    private val encoderLock = Any()
    private var codec: MediaCodec? = null
    private var onConfig: ((ByteArray) -> Unit)? = null
    private var onPacket: ((MediaPacket) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (running) return
        val chMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.CAMCORDER, sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT, max(minBuf * 2, 8192))
        } catch (e: Exception) {
            onError("麦克风初始化失败：${e.message}"); return
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release(); onError("麦克风不可用（可能没有权限或被占用）"); return
        }
        try {
            r.startRecording()
        } catch (e: Exception) {
            r.release(); onError("麦克风启动失败：${e.message}"); return
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release(); onError("麦克风被其他应用占用"); return
        }
        record = r
        running = true
        thread = Thread({ loop(r) }, "audio").also { it.start() }
    }

    fun stopCapture() {
        running = false
        thread?.join(1000); thread = null
        record?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        record = null
    }

    fun attachEncoder(onConfig: (ByteArray) -> Unit, onPacket: (MediaPacket) -> Unit) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        // 序列头用标准 AAC-LC AudioSpecificConfig；编码器输出的 csd 到达后再覆盖
        onConfig(FlvMux.aacSequenceHeader(FlvMux.aacLcConfig(sampleRate, channels)))
        synchronized(encoderLock) {
            this.onConfig = onConfig; this.onPacket = onPacket; codec = c
        }
    }

    fun detachEncoder() {
        val c = synchronized(encoderLock) { val c = codec; codec = null; onConfig = null; onPacket = null; c }
        c?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
    }

    private fun loop(r: AudioRecord) {
        val samplesPerChunk = 1024 * channels
        val buf = ShortArray(samplesPerChunk)
        val bytes = ByteArray(samplesPerChunk * 2)
        val ts = AudioTimestamp()
        var framesRead = 0L
        var levelAcc = 0.0
        var levelCount = 0
        var startNs = -1L
        val info = MediaCodec.BufferInfo()
        while (running) {
            val n = r.read(buf, 0, buf.size)
            if (n <= 0) {
                if (n == AudioRecord.ERROR_DEAD_OBJECT) { onError("麦克风已断开"); break }
                continue
            }
            val frames = n / channels
            // 该块第一个采样点的采集时间（纳秒，System.nanoTime 时基）
            val ptsNs = if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                ts.nanoTime + (framesRead - ts.framePosition) * 1_000_000_000L / sampleRate
            } else {
                if (startNs < 0) startNs = System.nanoTime() - frames * 1_000_000_000L / sampleRate
                startNs + framesRead * 1_000_000_000L / sampleRate
            }
            framesRead += frames

            // 电平（静音时显示 0）
            var sum = 0.0
            for (i in 0 until n) { val s = buf[i].toDouble(); sum += s * s }
            levelAcc += sum; levelCount += n
            if (levelCount >= sampleRate / 10) {
                val rms = sqrt(levelAcc / levelCount) / 32768.0
                val db = if (rms > 0) 20 * log10(rms) else -90.0
                onLevel(if (muted) 0f else ((db + 60) / 60).toFloat().coerceIn(0f, 1f))
                levelAcc = 0.0; levelCount = 0
            }

            if (muted) java.util.Arrays.fill(buf, 0, n, 0.toShort())
            if (syncTest) {
                for (f in 0 until frames) {
                    val t = ptsNs + f * 1_000_000_000L / sampleRate
                    val inSec = t % 1_000_000_000L
                    val v = if (!muted && inSec < 50_000_000L) (Math.sin(2 * Math.PI * 1000 * inSec / 1e9) * 16000).toInt().toShort() else 0
                    for (ch in 0 until channels) buf[f * channels + ch] = v
                }
            }
            for (i in 0 until n) {
                bytes[i * 2] = (buf[i].toInt() and 0xFF).toByte()
                bytes[i * 2 + 1] = (buf[i].toInt() shr 8).toByte()
            }
            synchronized(encoderLock) {
                val c = codec ?: return@synchronized
                try {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = c.getInputBuffer(inIdx)!!
                        ib.clear(); ib.put(bytes, 0, n * 2)
                        c.queueInputBuffer(inIdx, 0, n * 2, ptsNs / 1000, 0)
                    }
                    drain(c, info)
                } catch (e: Exception) {
                    Log.e(TAG, "aac", e)
                    onError("音频编码失败：${e.message}")
                }
            }
        }
    }

    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                c.outputFormat.getByteBuffer("csd-0")?.let { b ->
                    val asc = ByteArray(b.remaining()).also { b.get(it) }
                    onConfig?.invoke(FlvMux.aacSequenceHeader(asc))
                }
                continue
            }
            if (idx < 0) break
            val ob = c.getOutputBuffer(idx)
            if (ob != null && info.size > 0) {
                ob.position(info.offset); ob.limit(info.offset + info.size)
                val raw = ByteArray(info.size).also { ob.get(it) }
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    onConfig?.invoke(FlvMux.aacSequenceHeader(raw))
                } else {
                    onPacket?.invoke(MediaPacket(MediaPacket.Type.AUDIO, info.presentationTimeUs, FlvMux.aacFrame(raw), false, false))
                }
            }
            c.releaseOutputBuffer(idx, false)
        }
    }

    companion object {
        private const val TAG = "AudioPipeline"
    }
}
