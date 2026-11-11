package app.livecomposer.arm

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 机械臂控制核心：位置设定点流式控制。
 *
 * - 由调用方以固定周期（建议 50Hz）调用 [tick]；每次按当前轨迹算出设定点，夹进限位后一帧同步写给全部舵机。
 * - 只下发位置设定点，不下发速度：链路一断，舵机停在最后一个设定点，不会自己跑。
 * - 保护：限位、力矩上限、负载超限急停、跟随误差过大急停。急停 = 原地保持（不卸力，避免举着手机掉下来）。
 * - 大臂、小臂/手腕避让：标定里有 [WristEnvelope] 时，大臂抬起前手腕先让开，到位后再回到目标；遥控时手腕自动跟着让。
 *
 * 不是线程安全的：全部方法在同一个控制线程里调用。
 */
class ArmController(
    private val bus: StsBus,
    val cal: ArmCalibration,
    private val config: Config = Config(),
    private val log: (String) -> Unit = {},
) {
    data class Config(
        val torqueLimitPermille: Int = 400,
        /** 轨迹速度上限：实机上小臂展开收回 6 秒（≈0.9 rad/s 峰值）会跟不上，12 秒可以，取 0.5。 */
        val maxVelRad: Double = 0.5,
        val maxAccRad: Double = 1.0,
        /** 遥控时每个关节的最大速度。 */
        val jogVelRad: Double = 0.6,
        val abortLoad: Int = 400,
        /** 设定点与实际位置相差超过它（tick）且持续 [followTimeoutS] 判为卡住。 */
        val followErrorTicks: Int = 120,
        val followTimeoutS: Double = 1.0,
        /** 每隔几个周期读一次负载和位置（总线带宽有限）。 */
        val monitorEvery: Int = 5,
        /** 大臂/小臂动时自动让手腕补偿，保持夹爪朝向。 */
        val keepLevel: Boolean = false,
    )

    enum class Mode { OFF, IDLE, MOVING, JOG, FAULT }

    data class State(
        val mode: Mode,
        val q: DoubleArray,
        val gripper: Double?,
        val loads: IntArray,
        val fault: String? = null,
    )

    private val n = cal.joints.size
    private var mode = Mode.OFF
    private var fault: String? = null
    private var setpoint = DoubleArray(n)
    private var gripperSet: Double? = null
    private var measured = DoubleArray(n)
    private var gripperMeasured: Double? = null
    private var loads = IntArray(n + 1)
    private var traj: Trajectory? = null
    /** 当前这次移动剩下的分段（避让时一次移动拆成“手腕先让开 → 其余关节 → 手腕到目标”）。 */
    private var segments = ArrayDeque<DoubleArray>()
    private var segGripper: Double? = null
    private var segDuration: Double? = null
    private var trajT = 0.0
    private var trajGripper: Pair<Double, Double>? = null
    private var queue = ArrayDeque<Keyframe>()
    private var holdLeft = 0.0
    private var jogVel = DoubleArray(n)
    private var levelRef: DoubleArray? = null
    private var ticks = 0
    private var stuckS = 0.0

    val state: State get() = State(mode, measured.copyOf(), gripperMeasured, loads.copyOf(), fault)

    /** 读取全部舵机当前位置。卸力时也能用（示教录制）。 */
    fun readPose(): Boolean {
        for ((i, j) in cal.joints.withIndex()) measured[i] = j.toRad(bus.position(j.servoId) ?: return false)
        cal.gripper?.let { g -> gripperMeasured = g.toOpen(bus.position(g.servoId) ?: return false) }
        return true
    }

    /** 上力：先把设定点设为当前位置再开扭矩，避免上电瞬间跳动。 */
    fun enable(): Boolean {
        if (!readPose()) return fail("读位置失败")
        for ((i, j) in cal.joints.withIndex()) {
            val t = j.clampTick(j.toTick(measured[i]))
            setpoint[i] = j.toRad(t)
        }
        gripperSet = gripperMeasured
        // 先压力矩上限再写目标：写目标位置会让舵机自动开扭矩，顺序反了会短暂以全力矩保持。
        fun retry(f: () -> Boolean) = (1..3).any { f() }
        for (id in cal.servoIds) if (!retry { bus.setTorqueLimit(id, config.torqueLimitPermille) }) return fail("舵机 $id 无应答（设力矩上限）")
        bus.syncGoal(goals())
        for (id in cal.servoIds) if (!retry { bus.setTorque(id, true) }) return fail("舵机 $id 无应答（上力）")
        fault = null; mode = Mode.IDLE; log("已上力")
        return true
    }

    /** 卸力（机械臂会因重力下垂，调用方需确认已扶住）。 */
    fun disable() {
        traj = null; queue.clear(); segments.clear(); jogVel.fill(0.0)
        for (id in cal.servoIds) bus.setTorque(id, false)
        mode = Mode.OFF; log("已卸力")
    }

    /** 急停：原地保持。 */
    fun stop(reason: String = "停止") {
        if (mode == Mode.OFF) return
        traj = null; queue.clear(); segments.clear(); jogVel.fill(0.0)
        if (readPose()) { setpoint = clampAll(measured.copyOf()); bus.syncGoal(goals()) }
        if (mode != Mode.FAULT) mode = Mode.IDLE
        log(reason)
    }

    fun clearFault() { if (mode == Mode.FAULT) { fault = null; mode = Mode.IDLE } }

    /** 移动到目标关节角（弧度），超出限位的关节被夹到边界。durationS 为空时按速度/加速度上限自动算。 */
    fun moveTo(q: DoubleArray, gripper: Double? = null, durationS: Double? = null): Boolean {
        if (mode == Mode.OFF || mode == Mode.FAULT) return false
        require(q.size == n)
        val target = withEnvelope(clampAll(q.copyOf()))
        segments = ArrayDeque(planSegments(setpoint, target))
        segGripper = gripper; segDuration = durationS
        levelRef = if (config.keepLevel) setpoint.copyOf() else null
        mode = Mode.MOVING
        return startSegment()
    }

    /**
     * 按碰撞包络把一次移动拆段：大臂路径上手腕需要的最大值高于当前手腕时，先只动手腕让开；
     * 再动其余关节（手腕不低于让开值）；最后手腕回到目标（不低于目标大臂角度对应的下限）。
     */
    private fun planSegments(from: DoubleArray, target: DoubleArray): List<DoubleArray> {
        val env = cal.wristEnvelope ?: return listOf(target)
        if (n < 5) return listOf(target)
        val need = env.maxAlong(from, target) ?: return listOf(target)
        if (need <= from[4] + 1e-6 && need <= target[4] + 1e-6) return listOf(target)
        val out = ArrayList<DoubleArray>()
        val clear = clampAll(doubleArrayOf(*from).also { it[4] = max(from[4], need) })
        if (clear[4] > from[4] + 1e-6) out += clear
        val main = target.copyOf().also { it[4] = max(target[4], need) }
        out += clampAll(main)
        if (target[4] < main[4] - 1e-6) out += target
        return out
    }

    private fun startSegment(): Boolean {
        val target = segments.removeFirstOrNull() ?: return false
        val last = segments.isEmpty()
        val minT = Trajectory.minDuration(setpoint, target, config.maxVelRad, config.maxAccRad)
        // 用户给的时长只作用在主段上（避让段按速度上限走）。
        val t = if (target.indices.any { it != 4 && abs(target[it] - setpoint[it]) > 1e-6 }) max(segDuration ?: 0.0, minT) else minT
        traj = Trajectory(setpoint.copyOf(), target, t); trajT = 0.0
        trajGripper = if (last) segGripper?.let { (gripperSet ?: it) to it.coerceIn(0.0, 1.0) } else null
        return true
    }

    /** 关节 5 不低于当前大臂/小臂角度对应的包络下限。 */
    private fun withEnvelope(q: DoubleArray): DoubleArray {
        val m = cal.wristEnvelope?.minWrist(q) ?: return q
        if (q[4] < m) { q[4] = m; clampAll(q) }
        return q
    }

    fun play(preset: Preset): Boolean {
        if (mode == Mode.OFF || mode == Mode.FAULT || preset.frames.isEmpty()) return false
        queue = ArrayDeque(preset.frames)
        return nextFrame()
    }

    /** 遥控：给每个关节一个 −1..1 的速度比例。持续调用；超过 [jogTimeoutS] 没更新就停。 */
    fun jog(velScale: DoubleArray) {
        if (mode == Mode.OFF || mode == Mode.FAULT) return
        traj = null; queue.clear()
        for (i in 0 until n) jogVel[i] = velScale[i].coerceIn(-1.0, 1.0) * config.jogVelRad
        jogAge = 0.0
        mode = if (jogVel.all { it == 0.0 }) Mode.IDLE else Mode.JOG
    }

    var jogTimeoutS = 0.3
    private var jogAge = 0.0

    /** 控制周期。dtS 为距上次调用的秒数。 */
    fun tick(dtS: Double) {
        if (mode == Mode.OFF) return
        ticks++
        when (mode) {
            Mode.MOVING -> {
                if (holdLeft > 0) { holdLeft -= dtS; if (holdLeft <= 0 && !nextFrame()) mode = Mode.IDLE }
                else traj?.let { tr ->
                    trajT += dtS
                    setpoint = clampAll(tr.at(trajT))
                    levelRef?.let { ref -> setpoint[4] = ArmKinematics.wristForLevel(setpoint, ref); setpoint = clampAll(setpoint) }
                    setpoint = withEnvelope(setpoint)
                    trajGripper?.let { (a, b) -> gripperSet = a + (b - a) * (trajT / tr.durationS).coerceIn(0.0, 1.0) }
                    if (tr.done(trajT)) {
                        traj = null
                        if (!startSegment()) { holdLeft = queueHold; if (holdLeft <= 0 && !nextFrame()) mode = Mode.IDLE }
                    }
                }
            }
            Mode.JOG -> {
                jogAge += dtS
                if (jogAge > jogTimeoutS) { jogVel.fill(0.0); mode = Mode.IDLE }
                else {
                    val prev = setpoint.copyOf()
                    for (i in 0 until n) setpoint[i] += jogVel[i] * dtS
                    setpoint = clampAll(setpoint)
                    jogEnvelope(prev, dtS)
                }
            }
            else -> {}
        }
        bus.syncGoal(goals())
        if (ticks % config.monitorEvery == 0) monitor(dtS * config.monitorEvery)
    }

    private var queueHold = 0.0

    /** 遥控时的避让：大臂要抬到手腕还没让开的位置，就先停住大臂、手腕以遥控速度转开；手腕不能往下压进包络。 */
    private fun jogEnvelope(prev: DoubleArray, dtS: Double) {
        val env = cal.wristEnvelope ?: return
        if (n < 5) return
        val need = env.minWrist(setpoint) ?: return
        if (setpoint[4] >= need) return
        val step = config.jogVelRad * dtS
        setpoint[4] = min(need, max(setpoint[4], prev[4]) + step)
        if (setpoint[4] < need) { setpoint[1] = prev[1]; setpoint[2] = prev[2] } // 手腕没让开前大臂、小臂原地等
        setpoint = clampAll(setpoint)
    }

    private fun nextFrame(): Boolean {
        val f = queue.removeFirstOrNull() ?: return false
        queueHold = f.holdS
        if (!moveTo(f.q, f.gripper, f.durationS)) return false
        return true
    }

    private fun monitor(periodS: Double) {
        var worst = 0
        for ((i, j) in cal.joints.withIndex()) {
            val p = bus.position(j.servoId) ?: return trip("舵机 ${j.servoId} 无应答")
            measured[i] = j.toRad(p)
            val l = bus.load(j.servoId) ?: 0
            loads[i] = l
            if (abs(l) > config.abortLoad) return trip("${j.name} 负载 $l 超限，可能撞到东西")
            worst = max(worst, abs(p - j.toTick(setpoint[i])))
        }
        cal.gripper?.let { g -> bus.position(g.servoId)?.let { gripperMeasured = g.toOpen(it) } }
        stuckS = if (worst > config.followErrorTicks) stuckS + periodS else 0.0
        if (stuckS > config.followTimeoutS) trip("关节跟不上设定点（偏差 $worst tick），可能卡住")
    }

    private fun trip(reason: String) {
        stop(reason); mode = Mode.FAULT; fault = reason; stuckS = 0.0
    }

    private fun fail(reason: String): Boolean { mode = Mode.FAULT; fault = reason; log(reason); return false }

    private fun clampAll(q: DoubleArray): DoubleArray {
        for ((i, j) in cal.joints.withIndex()) q[i] = min(max(q[i], j.minRad), j.maxRad)
        return q
    }

    private fun goals(): Map<Int, Int> {
        val m = LinkedHashMap<Int, Int>()
        for ((i, j) in cal.joints.withIndex()) m[j.servoId] = j.clampTick(j.toTick(setpoint[i]))
        val g = cal.gripper; val gs = gripperSet
        if (g != null && gs != null) m[g.servoId] = g.toTick(gs)
        return m
    }
}
