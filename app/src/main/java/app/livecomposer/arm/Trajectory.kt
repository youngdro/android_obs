package app.livecomposer.arm

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 多关节同步轨迹：所有关节同时开始、同时到达，按最小加加速度（五次多项式）插值，
 * 起止速度和加速度都为 0，运镜时没有顿挫。
 */
class Trajectory(val from: DoubleArray, val to: DoubleArray, val durationS: Double) {
    init { require(from.size == to.size && durationS > 0) }

    fun at(tS: Double): DoubleArray {
        val s = (tS / durationS).coerceIn(0.0, 1.0)
        val k = s * s * s * (10 - 15 * s + 6 * s * s)
        return DoubleArray(from.size) { from[it] + (to[it] - from[it]) * k }
    }

    fun done(tS: Double) = tS >= durationS

    companion object {
        /**
         * 按最大关节速度和加速度给出最短时长。五次多项式峰值速度 = 1.875·Δ/T，峰值加速度 ≈ 5.774·Δ/T²。
         */
        fun minDuration(from: DoubleArray, to: DoubleArray, maxVel: Double, maxAcc: Double): Double {
            var t = 0.05
            for (i in from.indices) {
                val d = abs(to[i] - from[i])
                t = max(t, max(1.875 * d / maxVel, sqrt(5.774 * d / maxAcc)))
            }
            return t
        }
    }
}

/** 预设动作：一串关键帧，每帧是 6 个关节角（弧度）+ 夹爪开合 + 到达用时。 */
data class Keyframe(val q: DoubleArray, val gripper: Double? = null, val durationS: Double = 2.0, val holdS: Double = 0.0)

data class Preset(val name: String, val frames: List<Keyframe>)
