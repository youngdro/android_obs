package app.livecomposer.mix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * 混音器：把各媒体源（拉流、本地视频）解码出的 PCM 混进麦克风那一路，再统一送 AAC 编码。
 *
 * - 生产者：播放器的音频线程调用 [configure] / [write]（任意采样率、声道数，16 位整型或 32 位浮点）。
 *   这里做下混 / 上混和线性插值重采样，统一成输出格式后写入各自的环形缓冲。
 * - 消费者：麦克风采集线程每读到一块就调用 [mixInto]，按各源音量叠加并限幅。
 *   麦克风是主时钟，所以媒体源与麦克风之间的时钟漂移靠缓冲水位控制：
 *   积压超过 [MAX_LATENCY_MS] 就丢掉最旧的数据，追回到 [TARGET_LATENCY_MS]；欠载则补静音并重新预充。
 * - 自适应缓冲：数据一阵一阵到达的源（例如网页声音，网页忙时会成批晚到）在“还在出声时”欠载，
 *   就把该源的缓冲目标加 [STEP_MS]（最多 [ADAPT_MAX_MS]），之后 [RELAX_MS] 内不再欠载才逐步回落。
 *
 * 纯 Kotlin，无 Android 依赖，可在 JVM 上测试（verify/）。
 */
class AudioMixer(val sampleRate: Int, val channels: Int, private val now: () -> Long = System::nanoTime) {

    private inner class Source {
        val cap = sampleRate * channels * 2 // 2 秒
        val ring = ShortArray(cap)
        var readPos = 0
        var size = 0
        @Volatile var volume = 1f
        @Volatile var level = 0f
        var primed = false
        /** 该源当前的缓冲目标（毫秒），按欠载情况自适应。 */
        var targetMs = TARGET_LATENCY_MS
        var lastWriteNs = 0L
        var lastUnderrunNs = 0L
        // 输入格式
        var inRate = 0
        var inChannels = 0
        var encoding = ENCODING_PCM_16BIT
        // 重采样状态
        val prev = FloatArray(2)
        var hasPrev = false
        var frac = 0.0
        var levelAcc = 0.0
        var levelN = 0

        fun push(v: Float) {
            val s = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            if (size == cap) { readPos = (readPos + 1) % cap; size-- }
            ring[(readPos + size) % cap] = s
            size++
        }
    }

    private val sources = ConcurrentHashMap<String, Source>()

    fun addSource(id: String, volume: Float = 1f) {
        sources.getOrPut(id) { Source() }.volume = volume
    }

    fun removeSource(id: String) { sources.remove(id) }

    fun setVolume(id: String, volume: Float) { sources[id]?.volume = volume.coerceIn(0f, 2f) }

    /** 该源当前的缓冲目标（毫秒），诊断用。 */
    fun targetMs(id: String): Int = sources[id]?.targetMs ?: 0

    /** 0..1 的电平（已乘音量），界面显示用。 */
    fun level(id: String): Float = sources[id]?.level ?: 0f

    /** 输入格式变化（播放器 flush / 切换码流时调用），清空该源缓冲。 */
    fun configure(id: String, inSampleRate: Int, inChannels: Int, encoding: Int) {
        val s = sources[id] ?: return
        synchronized(s) {
            s.inRate = inSampleRate; s.inChannels = inChannels; s.encoding = encoding
            s.size = 0; s.readPos = 0; s.primed = false; s.hasPrev = false; s.frac = 0.0
        }
    }

    /** 写入一段交错 PCM（小端）。不支持的编码直接忽略。 */
    fun write(id: String, data: ByteBuffer) {
        val s = sources[id] ?: return
        synchronized(s) {
            if (s.inRate <= 0 || s.inChannels <= 0) return
            val bytesPerSample = when (s.encoding) {
                ENCODING_PCM_16BIT -> 2
                ENCODING_PCM_FLOAT -> 4
                else -> return
            }
            s.lastWriteNs = now()
            val buf = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val frameBytes = bytesPerSample * s.inChannels
            val step = s.inRate.toDouble() / sampleRate
            val cur = FloatArray(2)
            while (buf.remaining() >= frameBytes) {
                // 读一帧并映射到输出声道
                var l = 0f; var r = 0f
                for (c in 0 until s.inChannels) {
                    val v = if (bytesPerSample == 2) buf.short / 32768f else buf.float
                    if (c == 0) l = v else if (c == 1) r = v
                }
                if (s.inChannels == 1) r = l
                if (channels == 1) { cur[0] = (l + r) / 2f } else { cur[0] = l; cur[1] = r }
                if (!s.hasPrev) {
                    s.prev[0] = cur[0]; s.prev[1] = cur[1]; s.hasPrev = true; s.frac = 0.0
                    continue
                }
                // 在 prev(t=0) 与 cur(t=1) 之间按输出采样间隔插值
                while (s.frac < 1.0) {
                    val f = s.frac.toFloat()
                    for (c in 0 until channels) {
                        val v = s.prev[c] + (cur[c] - s.prev[c]) * f
                        s.push(v)
                        s.levelAcc += (v * v).toDouble(); s.levelN++
                    }
                    s.frac += step
                }
                s.frac -= 1.0
                s.prev[0] = cur[0]; s.prev[1] = cur[1]
            }
            if (s.levelN >= sampleRate / 10) {
                s.level = (sqrt(s.levelAcc / s.levelN) * 2.5 * s.volume).toFloat().coerceIn(0f, 1f)
                s.levelAcc = 0.0; s.levelN = 0
            }
        }
    }

    /** 把所有源的 [frames] 帧叠加进 out（交错，已含麦克风），限幅到 16 位。 */
    fun mixInto(out: ShortArray, frames: Int) {
        if (sources.isEmpty()) return
        val n = frames * channels
        val acc = IntArray(n) { out[it].toInt() }
        var any = false
        val t = now()
        for (s in sources.values) {
            synchronized(s) {
                // 长时间没有欠载：缓冲目标逐步回落，减少延迟
                if (s.targetMs > TARGET_LATENCY_MS && t - s.lastUnderrunNs > RELAX_MS * 1_000_000L) {
                    s.targetMs = maxOf(TARGET_LATENCY_MS, s.targetMs - STEP_MS / 2); s.lastUnderrunNs = t
                }
                val target = sampleRate * channels * s.targetMs / 1000
                val maxBacklog = sampleRate * channels * (s.targetMs + MAX_LATENCY_MS - TARGET_LATENCY_MS) / 1000
                if (s.size > maxBacklog) {
                    val drop = s.size - target
                    s.readPos = (s.readPos + drop) % s.cap; s.size -= drop
                }
                if (!s.primed) {
                    if (s.size >= target) s.primed = true else return@synchronized
                }
                val take = minOf(n, s.size)
                val vol = s.volume
                for (i in 0 until take) {
                    acc[i] += (s.ring[(s.readPos + i) % s.cap] * vol).toInt()
                }
                s.readPos = (s.readPos + take) % s.cap; s.size -= take
                if (take < n) {
                    s.primed = false // 欠载：本块补静音，下次重新预充
                    // 源还在持续送数据（不是声音停了）却欠载：说明到达抖动比缓冲大，加大缓冲
                    if (t - s.lastWriteNs < 500_000_000L) {
                        s.targetMs = minOf(ADAPT_MAX_MS, s.targetMs + STEP_MS); s.lastUnderrunNs = t
                    }
                }
                any = true
            }
        }
        if (!any) return
        for (i in 0 until n) out[i] = acc[i].coerceIn(-32768, 32767).toShort()
    }

    companion object {
        // 与 android.media.AudioFormat 的取值一致
        const val ENCODING_PCM_16BIT = 2
        const val ENCODING_PCM_FLOAT = 4
        const val TARGET_LATENCY_MS = 120
        const val MAX_LATENCY_MS = 400
        const val STEP_MS = 80
        const val ADAPT_MAX_MS = 480
        const val RELAX_MS = 20_000L
    }
}
