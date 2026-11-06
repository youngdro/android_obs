import app.livecomposer.mix.AudioMixer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.system.exitProcess

/** 混音器检查：重采样后的频率与幅度、音量、限幅、积压控制、欠载补静音。 */
fun main() {
    var failed = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "PASS " else "FAIL ") + name + (if (detail.isNotEmpty()) "  ($detail)" else ""))
        if (!ok) failed++
    }

    // 44.1kHz 立体声 float，1kHz 正弦，幅度 0.5 → 48kHz 单声道
    val mixer = AudioMixer(48_000, 1)
    mixer.addSource("a", 1f)
    mixer.configure("a", 44_100, 2, AudioMixer.ENCODING_PCM_FLOAT)
    fun feed(seconds: Double, rate: Int = 44_100, amp: Double = 0.5) {
        val frames = (rate * seconds).toInt()
        val bb = ByteBuffer.allocate(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) { val v = (sin(2 * PI * 1000 * i / rate) * amp).toFloat(); bb.putFloat(v); bb.putFloat(v) }
        bb.flip(); mixer.write("a", bb)
    }
    feed(0.3)
    val out = ShortArray(4800) // 100ms
    mixer.mixInto(out, out.size)
    var crossings = 0
    for (i in 1 until out.size) if ((out[i - 1] < 0) != (out[i] < 0)) crossings++
    val peak = out.maxOf { abs(it.toInt()) }
    check("重采样频率 ≈ 1kHz", crossings in 196..204, "100ms 内过零 $crossings 次")
    check("幅度保持", peak in 15500..17000, "峰值 $peak")

    // 音量 0.5
    mixer.setVolume("a", 0.5f)
    val out2 = ShortArray(2400); mixer.mixInto(out2, out2.size)
    val peak2 = out2.maxOf { abs(it.toInt()) }
    check("音量 50%", peak2 in 7700..8500, "峰值 $peak2")

    // 与已有的麦克风信号叠加并限幅
    mixer.setVolume("a", 2f)
    feed(0.3)
    val out3 = ShortArray(2400) { 30000 }
    mixer.mixInto(out3, out3.size)
    check("叠加后限幅不溢出", out3.all { it >= -32768 && it <= 32767 } && out3.max() == 32767.toShort())

    // 积压控制：写入 1.5 秒后，一次混音应把积压裁到目标水位附近
    mixer.configure("a", 44_100, 2, AudioMixer.ENCODING_PCM_FLOAT)
    mixer.setVolume("a", 1f)
    feed(1.5)
    val big = ShortArray(48_000) // 1 秒
    mixer.mixInto(big, 4800)
    // 再取 1 秒：裁剪后只剩约 TARGET-100ms，后面应是静音
    val rest = ShortArray(48_000); mixer.mixInto(rest, rest.size)
    val lastNonZero = rest.indexOfLast { it.toInt() != 0 }
    check("积压超过 ${AudioMixer.MAX_LATENCY_MS}ms 时裁剪", lastNonZero in 0..(48 * AudioMixer.TARGET_LATENCY_MS),
        "裁剪后剩余 ${lastNonZero / 48}ms")

    // 欠载后需重新预充才输出
    feed(0.05)
    val small = ShortArray(480); mixer.mixInto(small, small.size)
    check("欠载后预充前不输出", small.all { it.toInt() == 0 })
    feed(0.2)
    mixer.mixInto(small, small.size)
    check("预充后恢复输出", small.any { it.toInt() != 0 })

    // 16 位整型单声道 22.05kHz → 48kHz 单声道
    val m2 = AudioMixer(48_000, 1)
    m2.addSource("b"); m2.configure("b", 22_050, 1, AudioMixer.ENCODING_PCM_16BIT)
    val frames = 22_050 / 2
    val bb = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until frames) bb.putShort((sin(2 * PI * 500 * i / 22_050) * 10000).toInt().toShort())
    bb.flip(); m2.write("b", bb)
    val o = ShortArray(4800); m2.mixInto(o, o.size)
    var c2 = 0
    for (i in 1 until o.size) if ((o[i - 1] < 0) != (o[i] < 0)) c2++
    check("16 位 22.05kHz 输入", c2 in 97..103, "100ms 内过零 $c2 次")

    // 不支持的编码忽略，删除源后不再输出
    m2.removeSource("b")
    val o2 = ShortArray(480); m2.mixInto(o2, o2.size)
    check("移除源后不再混入", o2.all { it.toInt() == 0 })

    // 自适应缓冲：网页声音成批到达（主线程忙，间隔在 100ms 和 400ms 之间交替），麦克风每 20ms 取一块
    var clock = 0L
    val m3 = AudioMixer(48_000, 1) { clock }
    m3.addSource("web"); m3.configure("web", 48_000, 1, AudioMixer.ENCODING_PCM_16BIT)
    var phase = 0
    fun burst(ms: Int) {
        val n = 48 * ms
        val b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) { b.putShort((sin(2 * PI * 1000 * phase / 48_000.0) * 10000).toInt().toShort()); phase++ }
        b.flip(); m3.write("web", b)
    }
    var gapsEarly = 0; var gapsLate = 0
    var nextAt = 0; var pending = 0; var longGap = false
    for (step in 0 until 500) { // 10 秒
        if (step == nextAt) { val gap = if (longGap) 20 else 5; burst(pending.coerceAtLeast(gap) * 20); pending = 0; nextAt += gap; longGap = !longGap }
        val blk = ShortArray(960); m3.mixInto(blk, blk.size)
        val silent = blk.count { it.toInt() == 0 } > 100
        if (silent && step >= 5) { if (step < 100) gapsEarly++ else if (step >= 350) gapsLate++ }
        clock += 20_000_000L
    }
    check("成批到达时先出现断续（复现）", gapsEarly > 0, "$gapsEarly 块")
    check("自动加大缓冲后不再断续", gapsLate == 0, "前 2 秒断续块 $gapsEarly，最后 3 秒 $gapsLate，缓冲 ${m3.targetMs("web")}ms")
    // 改为均匀到达，20 秒无欠载后逐步回落
    for (step in 0 until 3000) { burst(20); val blk = ShortArray(960); m3.mixInto(blk, blk.size); clock += 20_000_000L }
    check("到达恢复均匀后缓冲逐步回落", m3.targetMs("web") < 300, "${m3.targetMs("web")}ms")

    if (failed > 0) { println("$failed 项失败"); exitProcess(1) }
    println("ALL MIXER CHECKS PASSED")
}
