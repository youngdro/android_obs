package app.livecomposer.mix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 本地实时降噪（纯 Kotlin，不依赖系统音效，所有手机效果一致）。
 *
 * 做法：短时傅里叶变换（512 点、半重叠、sqrt-Hann 窗，48kHz 下约 10.7ms 一帧），
 * 每个频点用“最小值跟踪”估计稳态噪声（风扇、空调、电流声、环境底噪），
 * 再用判决引导的维纳滤波算增益，增益下限 [floorDb] 防止声音发“空”，并对增益做时间平滑抑制“音乐噪声”。
 *
 * - 固定延迟 [latencyFrames] 个采样点，不论开关与否都经过同一条延迟线，切换时时间轴不跳；
 *   调用方把输出的时间戳减去这段延迟即可与画面对齐。
 * - 开关切换时增益在约 100ms 内平滑过渡，不会“咔”一声。
 * - 只处理单声道 16bit；多声道时各声道取平均后处理再复制（麦克风这一路固定单声道）。
 *
 * 线程：只在音频采集线程调用 [process]；[enabled] 可在任意线程设置。
 */
class NoiseReducer(private val channels: Int = 1, private val floorDb: Float = -18f) {
    @Volatile var enabled: Boolean = true

    val latencyFrames: Int get() = N

    private val window = FloatArray(N) { sqrt(0.5f - 0.5f * cos(2.0 * PI * it / N).toFloat()) }
    private val inBuf = FloatArray(N)        // 最近 N 个输入采样
    private var inFill = N - HOP             // 开头补零，使延迟固定为 N
    private val outAcc = FloatArray(N)       // 重叠相加累积
    private val outQueue = FloatArray(HOP * 64)
    private var qHead = 0
    private var qSize = 0

    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val noise = FloatArray(BINS)
    private val smoothP = FloatArray(BINS)
    private val prevClean = FloatArray(BINS)
    private val gain = FloatArray(BINS) { 1f }
    private var frames = 0L
    /** 0 = 不降噪，1 = 全量降噪；在两者之间平滑过渡。 */
    private var mix = 1f
    private val gMin = Math.pow(10.0, floorDb / 20.0).toFloat()

    init {
        // 预先放入一跳的零，使输出队列始终领先输入一跳
        qSize = HOP
    }

    /** 原地处理 [frames] 帧交错 PCM（输出比输入晚 [latencyFrames] 帧）。 */
    fun process(buf: ShortArray, frames: Int) {
        for (f in 0 until frames) {
            var s = 0f
            for (c in 0 until channels) s += buf[f * channels + c]
            inBuf[inFill++] = s / channels / 32768f
            if (inFill == N) {
                processFrame()
                System.arraycopy(inBuf, HOP, inBuf, 0, N - HOP)
                inFill = N - HOP
            }
            val o = if (qSize > 0) {
                val v = outQueue[qHead]; qHead = (qHead + 1) % outQueue.size; qSize--; v
            } else 0f
            val v = (o * 32768f).toInt().coerceIn(-32768, 32767).toShort()
            for (c in 0 until channels) buf[f * channels + c] = v
        }
    }

    private fun processFrame() {
        for (i in 0 until N) { re[i] = inBuf[i] * window[i]; im[i] = 0f }
        fft(re, im, false)

        val target = if (enabled) 1f else 0f
        val step = HOP.toFloat() / (SAMPLE_RATE_HINT * 0.1f) // 约 100ms 完成过渡
        frames++
        val first = frames == 1L
        mix = if (first) target else if (mix < target) min(target, mix + step) else max(target, mix - step)
        for (k in 0 until BINS) {
            val p = re[k] * re[k] + im[k] * im[k] + 1e-12f
            // 噪声估计：平滑功率谱的最小值跟踪，缓慢上升（约 6dB/秒）以适应环境变化
            smoothP[k] = if (first) p else 0.7f * smoothP[k] + 0.3f * p
            noise[k] = if (first) p else min(noise[k] * RISE, max(smoothP[k], 1e-12f))
            val nk = noise[k] * OVER
            // 判决引导法：先验信噪比
            val post = p / nk
            val prio = 0.96f * prevClean[k] / nk + 0.04f * max(post - 1f, 0f)
            var g = prio / (1f + prio)
            g = max(g, gMin)
            // 增益时间平滑：下降稍慢，减少音乐噪声；上升快，保住语音起始
            g = if (g < gain[k]) 0.6f * gain[k] + 0.4f * g else g
            gain[k] = g
            prevClean[k] = g * g * p
            val applied = 1f - mix + mix * g
            re[k] *= applied; im[k] *= applied
            if (k != 0 && k != N / 2) { re[N - k] = re[k]; im[N - k] = -im[k] }
        }
        fft(re, im, true)
        for (i in 0 until N) outAcc[i] += re[i] * window[i]
        // 输出前 HOP 个完成的采样
        for (i in 0 until HOP) {
            if (qSize < outQueue.size) {
                outQueue[(qHead + qSize) % outQueue.size] = outAcc[i]; qSize++
            }
        }
        System.arraycopy(outAcc, HOP, outAcc, 0, N - HOP)
        java.util.Arrays.fill(outAcc, N - HOP, N, 0f)
    }

    companion object {
        private const val N = 512
        private const val HOP = N / 2
        private const val BINS = N / 2 + 1
        private const val SAMPLE_RATE_HINT = 48_000f
        /** 噪声估计每帧上升系数（约 6dB/秒 @ 48kHz、256 跳）。 */
        private val RISE = Math.pow(10.0, 0.6 / (48_000.0 / 256)).toFloat()
        /** 噪声过估计，最小值跟踪会低估噪声均值。 */
        private const val OVER = 2.0f

        /** 原地基 2 FFT；inverse=true 时含 1/N 归一化。 */
        private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = 2 * PI / len * (if (inverse) 1 else -1)
                val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
                var i = 0
                while (i < n) {
                    var cr = 1f; var ci = 0f
                    for (k in 0 until len / 2) {
                        val a = i + k; val b = a + len / 2
                        val xr = re[b] * cr - im[b] * ci
                        val xi = re[b] * ci + im[b] * cr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr; cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
            if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
        }
    }
}
