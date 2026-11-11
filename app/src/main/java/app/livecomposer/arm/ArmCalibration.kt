package app.livecomposer.arm

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * 机械臂标定：舵机读数（tick）与 URDF 关节角（弧度）的换算，以及软件限位。
 *
 * 角度 = sign × (tick − zeroTick) × 2π / 4096。软件限位以 tick 表示（实测范围两端各收约 5°），
 * 任何下发的目标都先夹在限位内。纯 Kotlin，安卓和电脑端共用。
 */
data class JointCal(
    val name: String,
    val servoId: Int,
    val zeroTick: Int,
    val sign: Int,
    val minTick: Int,
    val maxTick: Int,
) {
    init {
        require(sign == 1 || sign == -1) { "$name: sign 只能是 ±1" }
        require(minTick in 0..4095 && maxTick in 0..4095 && minTick <= maxTick) { "$name: 限位无效 $minTick..$maxTick" }
    }

    fun toRad(tick: Int): Double = sign * (tick - zeroTick) * TICK_RAD
    fun toTick(rad: Double): Int = zeroTick + (sign * rad / TICK_RAD).roundToInt()
    fun clampTick(tick: Int): Int = tick.coerceIn(minTick, maxTick)
    fun inRange(tick: Int): Boolean = tick in minTick..maxTick

    /** 以弧度表示的限位（已按方向排好大小）。 */
    val minRad: Double get() = minOf(toRad(minTick), toRad(maxTick))
    val maxRad: Double get() = maxOf(toRad(minTick), toRad(maxTick))

    companion object {
        const val TICKS_PER_REV = 4096
        const val TICK_RAD = 2 * PI / TICKS_PER_REV
    }
}

/** 夹爪：0 = 闭合，1 = 全开。 */
data class GripperCal(val servoId: Int, val closedTick: Int, val openTick: Int) {
    fun toTick(open: Double): Int = (closedTick + (openTick - closedTick) * open.coerceIn(0.0, 1.0)).roundToInt()
    fun toOpen(tick: Int): Double = ((tick - closedTick).toDouble() / (openTick - closedTick)).coerceIn(0.0, 1.0)
}

data class ArmCalibration(val joints: List<JointCal>, val gripper: GripperCal?) {
    val servoIds: List<Int> get() = joints.map { it.servoId } + listOfNotNull(gripper?.servoId)

    companion object {
        /**
         * 2026-11-11 对 a003 act 机械臂（6 关节 + 夹爪，ST3215）的实测标定。
         *
         * 限位 = 手动实测范围收 5°（底座、腕部旋转为线缆和单圈编码器限制的 ±165°）
         * 与舵机 EEPROM 里已存角度限位的交集：舵机本身会把目标夹在 EEPROM 限位内，超出部分到不了。
         * 以后若把 EEPROM 限位改成实测值，这里同步放宽。
         */
        val A003 = ArmCalibration(
            joints = listOf(
                JointCal("joint1", 1, 2121, +1, 794, 3060),   // 实测 244-3998，EEPROM 794-3060
                JointCal("joint2", 2, 3048, +1, 2038, 3048),  // 零位即收拢硬限位，只能上抬
                JointCal("joint3", 3, 3029, +1, 1386, 3029),  // 实测 1105-3029，EEPROM 1386-3030
                JointCal("joint4", 4, 2032, -1, 823, 2773),   // 实测 201-2773，EEPROM 823-3187
                JointCal("joint5", 5, 3005, -1, 548, 3031),   // 实测 325-3209，EEPROM 548-3031
                JointCal("joint6", 6, 2005, +1, 286, 3882),   // 实测 128-3882，EEPROM 286-3979
            ),
            gripper = GripperCal(7, closedTick = 2042, openTick = 3273),
        )
    }
}
