package app.livecomposer.arm

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 夹爪在空间中的位姿（底座坐标系，Z 朝上，单位米/弧度）。
 *
 * 朝向用夹爪自己的坐标系描述：X = 夹爪指向（从手腕指向指尖），Z = 夹爪张合方向的法线。
 * [yaw]/[pitch]/[roll] 是 ZYX 欧拉角：yaw = 指向在水平面上的方位，pitch = 指向的仰角（负为朝下），roll = 绕指向自转。
 */
data class ToolPose(val x: Double, val y: Double, val z: Double, val yaw: Double, val pitch: Double, val roll: Double) {
    val p: DoubleArray get() = doubleArrayOf(x, y, z)

    /** 旋转矩阵（行优先 3×3）。 */
    fun r(): DoubleArray = Rot.zyx(yaw, -pitch, roll) // Ry 正角让 X 朝下，所以仰角取负

    fun translated(dx: Double, dy: Double, dz: Double) = copy(x = x + dx, y = y + dy, z = z + dz)

    companion object {
        fun of(p: DoubleArray, r: DoubleArray): ToolPose {
            val (yaw, pitchNeg, roll) = Rot.toZyx(r)
            return ToolPose(p[0], p[1], p[2], yaw, -pitchNeg, roll)
        }
    }
}

/** 3×3 旋转矩阵的小工具（行优先）。 */
object Rot {
    fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { k ->
        val i = k / 3; val j = k % 3; a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
    }
    fun t(a: DoubleArray) = doubleArrayOf(a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8])
    fun mulV(a: DoubleArray, v: DoubleArray) = DoubleArray(3) { i -> a[i * 3] * v[0] + a[i * 3 + 1] * v[1] + a[i * 3 + 2] * v[2] }

    /** 绕轴 [a]（单位向量）转 [th]。 */
    fun axisAngle(a: DoubleArray, th: Double): DoubleArray {
        val c = cos(th); val s = sin(th); val t = 1 - c
        val x = a[0]; val y = a[1]; val z = a[2]
        return doubleArrayOf(
            t * x * x + c, t * x * y - s * z, t * x * z + s * y,
            t * x * y + s * z, t * y * y + c, t * y * z - s * x,
            t * x * z - s * y, t * y * z + s * x, t * z * z + c,
        )
    }

    /** R = Rz(a)·Ry(b)·Rx(c)。 */
    fun zyx(a: DoubleArray) = zyx(a[0], a[1], a[2])
    fun zyx(a: Double, b: Double, c: Double): DoubleArray =
        mul(mul(axisAngle(doubleArrayOf(0.0, 0.0, 1.0), a), axisAngle(doubleArrayOf(0.0, 1.0, 0.0), b)), axisAngle(doubleArrayOf(1.0, 0.0, 0.0), c))

    fun toZyx(r: DoubleArray): DoubleArray {
        val b = asin((-r[6]).coerceIn(-1.0, 1.0))
        return if (abs(r[6]) < 0.9999) doubleArrayOf(atan2(r[3], r[0]), b, atan2(r[7], r[8]))
        else doubleArrayOf(atan2(-r[1], r[4]), b, 0.0) // 万向锁：roll 并入 yaw
    }

    /** 从 a 转到 b 的旋转向量（轴 × 角度），世界坐标。 */
    fun errorVec(a: DoubleArray, b: DoubleArray): DoubleArray {
        val e = mul(b, t(a))
        val cosTh = ((e[0] + e[4] + e[8] - 1) / 2).coerceIn(-1.0, 1.0)
        val th = acos(cosTh)
        val v = doubleArrayOf(e[7] - e[5], e[2] - e[6], e[3] - e[1])
        if (th < 1e-9) return doubleArrayOf(v[0] / 2, v[1] / 2, v[2] / 2)
        if (PI - th < 1e-4) {
            // 接近 180°：从对称部分取轴
            val x = sqrt(max(0.0, (e[0] + 1) / 2)); val y = sqrt(max(0.0, (e[4] + 1) / 2)); val z = sqrt(max(0.0, (e[8] + 1) / 2))
            val ax = doubleArrayOf(x, if (e[1] >= 0) y else -y, if (e[2] >= 0) z else -z)
            return doubleArrayOf(ax[0] * th, ax[1] * th, ax[2] * th)
        }
        val k = th / (2 * sin(th))
        return doubleArrayOf(v[0] * k, v[1] * k, v[2] * k)
    }
}

/**
 * 逆运动学：给定夹爪位姿，求 6 个关节角。
 *
 * 用阻尼最小二乘（Levenberg–Marquardt）在关节空间迭代，雅可比用正运动学数值差分。每步之后把关节夹进
 * 标定限位，并把手腕抬到碰撞包络下限以上，所以解出来的关节角总是可以直接下发；目标够不着时给出最接近的姿态
 * 和剩余误差，由调用方决定是否接受。
 *
 * 纯 Kotlin，无 Android 依赖：电脑上的操控软件和安卓 App 用同一份。
 */
class ArmIk(
    private val cal: ArmCalibration,
    /** 夹爪工作点（两指中间、靠近指尖）在 link6 坐标系里的位置，米。 */
    val tcp: DoubleArray = DEFAULT_TCP,
) {
    data class Result(val q: DoubleArray, val posErr: Double, val rotErr: Double, val iterations: Int) {
        /** 位置误差 ≤ 2 mm 且朝向误差 ≤ 2° 视为够得着。 */
        val reached: Boolean get() = posErr <= 0.002 && rotErr <= Math.toRadians(2.0)
    }

    /** 朝向权重：1 米位置误差对应多少弧度朝向误差。手臂尺度约 0.2 米，取 0.1 让两者量级相当。 */
    var rotWeight = 0.1

    /** 关节角 → 夹爪位姿。 */
    fun forward(q: DoubleArray): ToolPose {
        val f = ArmKinematics.forward(q)
        val p = Rot.mulV(f.r, tcp).let { doubleArrayOf(f.p[0] + it[0], f.p[1] + it[1], f.p[2] + it[2]) }
        return ToolPose.of(p, Rot.mul(f.r, LINK6_TO_TOOL))
    }

    /** 关节角 → 夹爪位置和旋转矩阵。 */
    fun fk(q: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val f = ArmKinematics.forward(q)
        val p = Rot.mulV(f.r, tcp).let { doubleArrayOf(f.p[0] + it[0], f.p[1] + it[1], f.p[2] + it[2]) }
        return p to Rot.mul(f.r, LINK6_TO_TOOL)
    }

    /**
     * 从 [seed] 出发求解。[positionOnly] 为真时只管位置，朝向自由（够不着指定朝向时用来找“大致能到”的姿态）。
     */
    fun solve(target: ToolPose, seed: DoubleArray, maxIter: Int = 100, positionOnly: Boolean = false): Result =
        solve(target.p, target.r(), seed, maxIter, positionOnly)

    /** 同上，目标直接给位置和旋转矩阵（点动时用，避开欧拉角在夹爪竖直时的万向锁）。 */
    fun solve(tp: DoubleArray, tr: DoubleArray, seed: DoubleArray, maxIter: Int = 100, positionOnly: Boolean = false): Result {
        var q = project(seed.copyOf())
        var lambda = 0.01
        var (err, cost) = error(q, tp, tr, positionOnly)
        var it = 0
        while (it < maxIter) {
            it++
            if (cost < 1e-10) break
            val m = if (positionOnly) 3 else 6
            val j = jacobian(q, positionOnly)
            // (JᵀJ + λ·diag(JᵀJ) + λI)·dq = Jᵀe
            val jtj = Array(6) { a -> DoubleArray(6) { b -> (0 until m).sumOf { j[it][a] * j[it][b] } } }
            val jte = DoubleArray(6) { a -> (0 until m).sumOf { j[it][a] * err[it] } }
            var improved = false
            for (tries in 0 until 6) {
                val a = Array(6) { r -> DoubleArray(6) { c -> jtj[r][c] + if (r == c) lambda * (jtj[r][r] + 1e-4) else 0.0 } }
                val dq = solve6(a, jte) ?: break
                // 每步关节变化不超过 0.3 rad，避免数值大跳
                val s = dq.maxOf { abs(it) }.let { if (it > 0.3) 0.3 / it else 1.0 }
                val qn = project(DoubleArray(6) { q[it] + dq[it] * s })
                val (en, cn) = error(qn, tp, tr, positionOnly)
                if (cn < cost) { q = qn; err = en; cost = cn; lambda = max(lambda / 3, 1e-6); improved = true; break }
                lambda *= 4
            }
            if (!improved) break
        }
        val (p, r) = fk(q)
        val pe = sqrt((0..2).sumOf { (p[it] - tp[it]).let { d -> d * d } })
        val re = Rot.errorVec(r, tr).let { sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) }
        return Result(q, pe, re, it)
    }

    /**
     * 绝对目标：从当前姿态和若干预设种子分别求解，取够得着的里面离当前最近的；都够不着时取误差最小的。
     */
    fun solveBest(target: ToolPose, current: DoubleArray): Result {
        val seeds = listOf(current) + SEEDS
        var best: Result? = null
        fun score(r: Result) = (if (r.reached) 0.0 else 100.0 + r.posErr * 100 + r.rotErr) + dist(r.q, current) * 0.01
        for (s in seeds) {
            val r = solve(target, s)
            if (best == null || score(r) < score(best)) best = r
            if (r.reached && s === current) break
        }
        if (!best!!.reached) for (s in GRID) {
            val r = solve(target, s)
            if (score(r) < score(best!!)) best = r
        }
        return best!!
    }

    /** 把关节角夹进限位，并让手腕不低于包络下限。 */
    fun project(q: DoubleArray): DoubleArray {
        for ((i, j) in cal.joints.withIndex()) q[i] = min(max(q[i], j.minRad), j.maxRad)
        cal.wristEnvelope?.minWrist(q)?.let { m -> if (q[4] < m) q[4] = min(m, cal.joints[4].maxRad) }
        return q
    }

    private fun error(q: DoubleArray, tp: DoubleArray, tr: DoubleArray, positionOnly: Boolean): Pair<DoubleArray, Double> {
        val (p, r) = fk(q)
        val e = DoubleArray(6)
        for (i in 0..2) e[i] = tp[i] - p[i]
        if (!positionOnly) Rot.errorVec(r, tr).forEachIndexed { i, v -> e[3 + i] = v * rotWeight }
        return e to e.sumOf { it * it }
    }

    private fun jacobian(q: DoubleArray, positionOnly: Boolean): Array<DoubleArray> {
        val h = 1e-6
        val (p0, r0) = fk(q)
        val jac = Array(6) { DoubleArray(6) }
        for (k in 0 until 6) {
            val qk = q.copyOf(); qk[k] += h
            val (p, r) = fk(qk)
            for (i in 0..2) jac[i][k] = (p[i] - p0[i]) / h
            if (!positionOnly) Rot.errorVec(r0, r).forEachIndexed { i, v -> jac[3 + i][k] = v / h * rotWeight }
        }
        return jac
    }

    /** 6×6 线性方程组，部分主元高斯消元；奇异时返回 null。 */
    private fun solve6(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { r -> a[r].copyOf() + b[r] }
        for (c in 0 until n) {
            val piv = (c until n).maxBy { abs(m[it][c]) }
            if (abs(m[piv][c]) < 1e-12) return null
            val t = m[c]; m[c] = m[piv]; m[piv] = t
            for (r in c + 1 until n) {
                val f = m[r][c] / m[c][c]
                for (k in c..n) m[r][k] -= f * m[c][k]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) x[r] = (m[r][n] - (r + 1 until n).sumOf { m[r][it] * x[it] }) / m[r][r]
        return x
    }

    private fun dist(a: DoubleArray, b: DoubleArray) = sqrt(a.indices.sumOf { (a[it] - b[it]).let { d -> d * d } })

    companion object {
        /** link6 原点到夹爪工作点：link6 网格最低点约 −97 mm，两指中间取 −85 mm。 */
        val DEFAULT_TCP = doubleArrayOf(0.002, -0.0014, -0.085)

        /** 夹爪坐标系：X = link6 的 −Z（指向指尖），Z = link6 的 +X，Y = Z × X。 */
        val LINK6_TO_TOOL = doubleArrayOf(
            0.0, 0.0, 1.0,
            0.0, 1.0, 0.0,
            -1.0, 0.0, 0.0,
        )

        private fun deg(vararg d: Double) = DoubleArray(d.size) { Math.toRadians(d[it]) }
        /** 粗网格种子：常规种子都够不着时再试，覆盖底座朝向、大臂小臂伸缩和手腕的几种组合。 */
        private val GRID = buildList {
            for (j1 in listOf(-120.0, -60.0, 0.0, 60.0, 120.0)) for (j2 in listOf(-20.0, -70.0, -130.0)) for (j3 in listOf(-30.0, -100.0, -160.0))
                for (j5 in listOf(20.0, 100.0)) for (j4 in listOf(-60.0, 60.0)) add(deg(j1, j2, j3, j4, j5, 0.0))
        }
        private val SEEDS = listOf(
            deg(0.0, -45.0, -90.0, 0.0, 45.0, 0.0),
            deg(0.0, -90.0, -120.0, 0.0, 60.0, 0.0),
            deg(0.0, -30.0, -60.0, 0.0, 30.0, 0.0),
            deg(0.0, -120.0, -150.0, 0.0, 90.0, 0.0),
            deg(90.0, -60.0, -90.0, 0.0, 45.0, 0.0),
            deg(-90.0, -60.0, -90.0, 0.0, 45.0, 0.0),
        )
    }
}
