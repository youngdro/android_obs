package app.livecomposer.arm

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * a003 机械臂的正运动学，数据取自 URDF（a003_act_arm_description，SolidWorks 导出）：
 * 每个关节相对父连杆的原点（米）与转轴，所有 rpy 都是 0。Z 朝上，零位时大臂朝 +Y 水平伸出、小臂折回。
 */
object ArmKinematics {
    private val origins = arrayOf(
        doubleArrayOf(0.0, -0.030496, 0.056736),
        doubleArrayOf(0.019, -0.00039269, 0.044998),
        doubleArrayOf(0.0, 0.16599, 0.0014486),
        doubleArrayOf(-0.019, -0.17223, 0.025498),
        doubleArrayOf(0.019, -0.017586, 0.067099),
        doubleArrayOf(-0.06725, -0.017165, 0.018851),
    )
    private val axes = arrayOf(
        doubleArrayOf(0.0, 0.0087265, -0.99996),
        doubleArrayOf(-1.0, 0.0, 0.0),
        doubleArrayOf(1.0, 0.0, 0.0),
        doubleArrayOf(0.0, -0.0087265, 0.99996),
        doubleArrayOf(-1.0, 0.0, 0.0),
        doubleArrayOf(0.0, -0.0087265, 0.99996),
    ).map { a -> val n = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); doubleArrayOf(a[0] / n, a[1] / n, a[2] / n) }

    /** 末端（link6 坐标系）位姿：旋转矩阵（行优先 3×3）和位置。 */
    class Pose(val r: DoubleArray, val p: DoubleArray) {
        /** 末端坐标系某个轴在世界坐标里的方向：0=X 1=Y 2=Z。 */
        fun axis(i: Int) = doubleArrayOf(r[i], r[3 + i], r[6 + i])
    }

    fun forward(q: DoubleArray): Pose {
        require(q.size == 6)
        var r = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        var p = doubleArrayOf(0.0, 0.0, 0.0)
        for (i in 0 until 6) {
            p = add(p, mulV(r, origins[i]))
            r = mulM(r, rot(axes[i], q[i]))
        }
        return Pose(r, p)
    }

    /** 各关节原点在底座坐标系里的位置（含底座原点，共 7 个点），画骨架用。 */
    fun chain(q: DoubleArray): List<DoubleArray> {
        var r = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        var p = doubleArrayOf(0.0, 0.0, 0.0)
        val out = arrayListOf(p)
        for (i in 0 until 6) {
            p = add(p, mulV(r, origins[i]))
            r = mulM(r, rot(axes[i], q[i]))
            out += p
        }
        return out
    }

    private fun rot(a: DoubleArray, th: Double): DoubleArray {
        val c = cos(th); val s = sin(th); val t = 1 - c
        val (x, y, z) = Triple(a[0], a[1], a[2])
        return doubleArrayOf(
            t * x * x + c, t * x * y - s * z, t * x * z + s * y,
            t * x * y + s * z, t * y * y + c, t * y * z - s * x,
            t * x * z - s * y, t * y * z + s * x, t * z * z + c,
        )
    }
    private fun mulM(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { k ->
        val i = k / 3; val j = k % 3; a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
    }
    private fun mulV(a: DoubleArray, v: DoubleArray) = DoubleArray(3) { i -> a[i * 3] * v[0] + a[i * 3 + 1] * v[1] + a[i * 3 + 2] * v[2] }
    private fun add(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] + b[it] }

    /**
     * 保持末端俯仰：关节 2、3、5 的转轴平行（分别是 −X、+X、−X），关节 4 接近 0 时，
     * 末端绕 X 的俯仰 = −q2 + q3 − q5。大臂、小臂变化时让手腕反向补偿，夹爪（手机）朝向不变。
     * 关节 4 偏离 0 较多时只是近似，以后换成逆运动学。
     */
    fun wristForLevel(q: DoubleArray, reference: DoubleArray): Double =
        reference[4] - (q[1] - reference[1]) + (q[2] - reference[2])
}
