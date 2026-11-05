import app.livecomposer.mix.NoiseReducer
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * 降噪检查（48kHz 单声道）：
 * 1. 关闭时输出 = 输入延迟 latencyFrames（无损直通）；
 * 2. 开启时，纯底噪段（白噪声 + 50Hz 哼声）能量下降 ≥ 10dB；
 * 3. “人声”段（多谐波浊音，起伏包络）在噪声下保留，能量损失 ≤ 3dB；
 * 4. 开关切换不产生突跳（相邻采样差不超过信号本身的量级）。
 */
fun main() {
    var failed = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "PASS " else "FAIL ") + name + (if (detail.isNotEmpty()) "  ($detail)" else ""))
        if (!ok) failed++
    }
    val sr = 48_000
    val rnd = Random(1)
    fun noise(i: Int) = rnd.nextGaussian() * 600 + sin(2 * PI * 50 * i / sr) * 400
    fun voice(i: Int): Double {
        val t = i.toDouble() / sr
        val env = 0.5 + 0.5 * sin(2 * PI * 3 * t) // 音节起伏
        val f0 = 160 + 20 * sin(2 * PI * 0.7 * t)
        var v = 0.0
        for (h in 1..12) v += sin(2 * PI * f0 * h * t) / h
        return v * 5000 * env
    }
    fun run(nr: NoiseReducer, input: ShortArray): ShortArray {
        val out = input.copyOf()
        var i = 0
        while (i < out.size) {
            val n = minOf(1024, out.size - i)
            val chunk = out.copyOfRange(i, i + n)
            nr.process(chunk, n)
            System.arraycopy(chunk, 0, out, i, n)
            i += n
        }
        return out
    }
    fun rms(a: ShortArray, from: Int, to: Int): Double {
        var s = 0.0; for (i in from until to) s += a[i].toDouble() * a[i]; return sqrt(s / (to - from))
    }
    fun db(x: Double) = 20 * log10(x)

    // 1. 直通
    run {
        val nr = NoiseReducer(); nr.enabled = false
        val lat = nr.latencyFrames
        val input = ShortArray(sr) { (voice(it) + noise(it)).toInt().coerceIn(-32768, 32767).toShort() }
        val out = run(nr, input)
        var maxErr = 0
        for (i in lat until input.size) maxErr = maxOf(maxErr, abs(out[i] - input[i - lat]))
        check("关闭时无损直通（延迟 $lat 帧）", maxErr <= 2, "最大误差 $maxErr")
    }

    // 2/3. 前 3 秒只有底噪（让噪声估计收敛），之后 3 秒人声 + 底噪
    val n = sr * 6
    val noiseOnly = DoubleArray(n) { noise(it) }
    val clean = DoubleArray(n) { if (it >= sr * 3) voice(it) else 0.0 }
    val input = ShortArray(n) { (clean[it] + noiseOnly[it]).toInt().coerceIn(-32768, 32767).toShort() }
    val nr = NoiseReducer()
    val lat = nr.latencyFrames
    val out = run(nr, input)
    val inNoise = rms(input, sr * 2, sr * 3)
    val outNoise = rms(out, sr * 2 + lat, sr * 3 + lat)
    check("纯底噪压低 ≥ 10dB", db(inNoise / outNoise) >= 10, "%.1f dB".format(db(inNoise / outNoise)))
    // 人声保留：比较输出与纯净人声的能量
    val cleanS = ShortArray(n) { clean[it].toInt().toShort() }
    val vIn = rms(cleanS, sr * 4, sr * 6)
    val vOut = rms(out, sr * 4 + lat, sr * 6)
    check("人声能量损失 ≤ 3dB", db(vIn / vOut) <= 3 && db(vOut / vIn) <= 2, "%.1f dB".format(db(vOut / vIn)))
    // 信噪比改善
    var errIn = 0.0; var errOut = 0.0; var sig = 0.0
    for (i in sr * 4 until sr * 6 - lat) {
        sig += clean[i] * clean[i]
        errIn += (input[i] - clean[i]).let { it * it }
        errOut += (out[i + lat] - clean[i]).let { it * it }
    }
    val snrIn = 10 * log10(sig / errIn); val snrOut = 10 * log10(sig / errOut)
    check("人声段信噪比提升", snrOut > snrIn + 3, "%.1f → %.1f dB".format(snrIn, snrOut))

    // 4. 开关切换无突跳
    run {
        val nr2 = NoiseReducer()
        val inp = ShortArray(sr * 4) { (voice(it) + noise(it)).toInt().coerceIn(-32768, 32767).toShort() }
        val o = inp.copyOf()
        var i = 0; var maxJump = 0
        var maxInJump = 0
        for (k in 1 until inp.size) maxInJump = maxOf(maxInJump, abs(inp[k] - inp[k - 1]))
        while (i < o.size) {
            if (i % (sr / 2) == 0) nr2.enabled = !nr2.enabled
            val m = minOf(480, o.size - i)
            val c = o.copyOfRange(i, i + m); nr2.process(c, m); System.arraycopy(c, 0, o, i, m); i += m
        }
        for (k in 1 until o.size) maxJump = maxOf(maxJump, abs(o[k] - o[k - 1]))
        check("开关切换无突跳", maxJump <= maxInJump * 1.2, "输出最大跳变 $maxJump，输入 $maxInJump")
    }

    // 性能：1 分钟音频的处理耗时
    run {
        val nr3 = NoiseReducer()
        val buf = ShortArray(1024) { (noise(it)).toInt().toShort() }
        val t0 = System.nanoTime()
        repeat(sr * 60 / 1024) { nr3.process(buf, 1024) }
        val ms = (System.nanoTime() - t0) / 1e6
        check("处理速度（1 分钟音频）", ms < 3000, "%.0f ms".format(ms))
    }

    if (failed > 0) { println("$failed 项失败"); exitProcess(1) }
    println("全部通过")
}
