package app.livecomposer.arm

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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

    enum class Mode { OFF, IDLE, MOVING, JOG, JOG_TOOL, PLAYBACK, FAULT }

    data class State(
        val mode: Mode,
        val q: DoubleArray,
        val gripper: Double?,
        val loads: IntArray,
        val fault: String? = null,
        /** 设定点（下发给舵机的目标），界面画“目标姿态”用。 */
        val setpoint: DoubleArray = q,
        /** 空间点动时目标够不着（到了限位、包络或工作空间边缘）。 */
        val toolBlocked: Boolean = false,
        val recording: Boolean = false,
        /** 回放进度 0..1，不在回放时为 null。 */
        val playbackProgress: Double? = null,
    )

    /** 逆运动学，和控制器用同一份标定（限位、手腕包络）。 */
    val ik = ArmIk(cal)

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

    val state: State get() = State(
        mode, measured.copyOf(), gripperMeasured, loads.copyOf(), fault, setpoint.copyOf(), toolBlocked, recorder != null,
        playback?.let { (pbT / it.last().first).coerceIn(0.0, 1.0) },
    )

    // —— 空间点动 ——
    private var toolVel = DoubleArray(6)
    private var toolFrame = false
    private var toolBlocked = false
    private var toolTarget: Pair<DoubleArray, DoubleArray>? = null

    // —— 夹爪单独开合 ——
    private var gripperTarget: Double? = null

    // —— 连续轨迹录制 / 回放 ——
    private var recorder: MutableList<DoubleArray>? = null
    private var recT = 0.0
    private var playback: List<Pair<Double, DoubleArray>>? = null
    private var pendingPlayback: List<Pair<Double, DoubleArray>>? = null
    private var pbT = 0.0

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
        traj = null; queue.clear(); segments.clear(); jogVel.fill(0.0); toolVel.fill(0.0); playback = null; pendingPlayback = null; gripperTarget = null
        for (id in cal.servoIds) bus.setTorque(id, false)
        mode = Mode.OFF; log("已卸力")
    }

    /** 急停：原地保持。 */
    fun stop(reason: String = "停止") {
        if (mode == Mode.OFF) return
        traj = null; queue.clear(); segments.clear(); jogVel.fill(0.0); toolVel.fill(0.0); playback = null; pendingPlayback = null; gripperTarget = null
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

    /**
     * 让夹爪移动到空间位姿：先求逆解，够得着才走（关节空间同步轨迹，避让照常生效）。返回逆解结果，没上力时返回 null。
     */
    fun moveToPose(target: ToolPose, gripper: Double? = null, durationS: Double? = null, requireReach: Boolean = true): ArmIk.Result? {
        if (mode == Mode.OFF || mode == Mode.FAULT) return null
        val r = ik.solveBest(target, setpoint)
        if (r.reached || !requireReach) moveTo(r.q, gripper, durationS)
        return r
    }

    /**
     * 空间点动：[vel] = (vx, vy, vz 米/秒, wx, wy, wz 弧度/秒)，[inToolFrame] 为真时按夹爪自身坐标系
     * （X = 指向，往前伸），否则按底座坐标系。持续调用；超过 [jogTimeoutS] 没更新就停。
     */
    fun jogTool(vel: DoubleArray, inToolFrame: Boolean = false) {
        if (mode == Mode.OFF || mode == Mode.FAULT) return
        traj = null; queue.clear(); segments.clear(); jogVel.fill(0.0); playback = null; pendingPlayback = null
        if (mode != Mode.JOG_TOOL || toolFrame != inToolFrame) toolTarget = null
        for (i in 0 until 6) toolVel[i] = vel[i]
        toolFrame = inToolFrame
        jogAge = 0.0
        mode = if (toolVel.all { it == 0.0 }) Mode.IDLE else Mode.JOG_TOOL
    }

    /** 夹爪开合 0..1，平滑过去（约 1 秒走完全程）。 */
    fun setGripper(open: Double) {
        if (mode == Mode.OFF || mode == Mode.FAULT || cal.gripper == null) return
        gripperTarget = open.coerceIn(0.0, 1.0)
    }

    /** 开始录制连续轨迹（上力时录设定点，卸力时录手掰的实际位置）。 */
    fun startRecording() { recorder = mutableListOf(); recT = RECORD_PERIOD_S; record() }

    /** 结束录制；少于 2 个采样返回 null。 */
    fun stopRecording(name: String): Recording? {
        val r = recorder ?: return null
        recorder = null
        return if (r.size < 2) null else Recording(name, RECORD_PERIOD_S, r.toList())
    }

    /**
     * 回放连续轨迹：先慢速走到轨迹起点（避让照常），再按录制节奏回放。每个采样都夹进限位、套手腕包络，
     * 相邻采样之间关节变化超过速度上限的地方自动放慢。[speed] > 1 加快，< 1 放慢。
     */
    fun playRecording(rec: Recording, speed: Double = 1.0): Boolean {
        if (mode == Mode.OFF || mode == Mode.FAULT || rec.samples.size < 2) return false
        val vmax = max(config.maxVelRad, config.jogVelRad)
        val pts = smooth(rec.samples).map { s -> s to withEnvelope(clampAll(s.copyOf(n))) }
        val timed = ArrayList<Pair<Double, DoubleArray>>()
        var t = 0.0
        for ((k, pair) in pts.withIndex()) {
            val (raw, q) = pair
            if (k > 0) {
                val prev = timed.last().second
                val d = (0 until n).maxOf { abs(q[it] - prev[it]) }
                t += max(rec.periodS / speed, d / vmax)
            }
            timed += t to (q + raw.copyOfRange(n, raw.size))
        }
        val start = timed.first().second
        pendingPlayback = timed
        val g = start.getOrNull(n)?.takeIf { !it.isNaN() }
        moveTo(start.copyOf(n), g)
        return true
    }

    private fun smooth(s: List<DoubleArray>): List<DoubleArray> =
        s.indices.map { i ->
            val a = s[max(0, i - 1)]; val b = s[i]; val c = s[min(s.size - 1, i + 1)]
            DoubleArray(b.size) { k -> if (b[k].isNaN()) b[k] else (a[k] + 2 * b[k] + c[k]) / 4 }
        }

    private fun record() {
        val r = recorder ?: return
        val g = (if (mode == Mode.OFF) gripperMeasured else gripperSet) ?: Double.NaN
        r += (if (mode == Mode.OFF) measured else setpoint).copyOf() + g
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
        if (recorder != null) {
            recT += dtS
            if (recT >= RECORD_PERIOD_S) {
                recT -= RECORD_PERIOD_S
                if (mode == Mode.OFF) readPose()
                record()
            }
        }
        if (mode == Mode.OFF) return
        ticks++
        when (mode) {
            Mode.MOVING -> {
                if (holdLeft > 0) { holdLeft -= dtS; if (holdLeft <= 0 && !nextFrame()) finishMove() }
                else traj?.let { tr ->
                    trajT += dtS
                    setpoint = clampAll(tr.at(trajT))
                    levelRef?.let { ref -> setpoint[4] = ArmKinematics.wristForLevel(setpoint, ref); setpoint = clampAll(setpoint) }
                    setpoint = withEnvelope(setpoint)
                    trajGripper?.let { (a, b) -> gripperSet = a + (b - a) * (trajT / tr.durationS).coerceIn(0.0, 1.0) }
                    if (tr.done(trajT)) {
                        traj = null
                        if (!startSegment()) { holdLeft = queueHold; if (holdLeft <= 0 && !nextFrame()) finishMove() }
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
            Mode.JOG_TOOL -> {
                jogAge += dtS
                if (jogAge > jogTimeoutS) { toolVel.fill(0.0); mode = Mode.IDLE } else jogToolStep(dtS)
            }
            Mode.PLAYBACK -> playback?.let { pb ->
                pbT += dtS
                val k = pb.indexOfFirst { it.first >= pbT }
                if (k < 0) { setpoint = withEnvelope(clampAll(pb.last().second.copyOf(n))); playback = null; mode = Mode.IDLE }
                else {
                    val (t1, b) = pb[k]; val (t0, a) = pb[max(0, k - 1)]
                    val u = if (t1 > t0) ((pbT - t0) / (t1 - t0)).coerceIn(0.0, 1.0) else 1.0
                    setpoint = withEnvelope(clampAll(DoubleArray(n) { a[it] + (b[it] - a[it]) * u }))
                    val ga = a.getOrNull(n); val gb = b.getOrNull(n)
                    if (ga != null && gb != null && !ga.isNaN() && !gb.isNaN()) gripperSet = ga + (gb - ga) * u
                }
            }
            else -> {}
        }
        gripperTarget?.let { gt ->
            if (trajGripper == null || mode != Mode.MOVING) {
                val g = gripperSet ?: gt
                val step = GRIPPER_SPEED * dtS
                gripperSet = if (abs(gt - g) <= step) gt.also { gripperTarget = null } else g + step * Math.signum(gt - g)
            }
        }
        bus.syncGoal(goals())
        if (ticks % config.monitorEvery == 0) monitor(dtS * config.monitorEvery)
    }

    private var queueHold = 0.0

    private fun finishMove() {
        val pb = pendingPlayback
        if (pb != null) { pendingPlayback = null; playback = pb; pbT = 0.0; mode = Mode.PLAYBACK } else mode = Mode.IDLE
    }

    /** 空间点动一步：当前设定点的夹爪位姿按速度挪一点，逆解（从当前关节角出发）够得着才走，关节速度超限就按比例放慢。 */
    private fun jogToolStep(dtS: Double) {
        // 起点就在包络里（例如刚上力时手腕在 0°、小臂收拢）：先以点动速度把手腕转出来，再开始空间点动
        cal.wristEnvelope?.minWrist(setpoint)?.let { m ->
            if (setpoint[4] < m - 1e-6) {
                setpoint[4] = min(m, setpoint[4] + config.jogVelRad * dtS)
                setpoint = clampAll(setpoint); toolTarget = null; toolBlocked = false
                return
            }
        }
        // 目标位姿单独积分，不每步从设定点重算，否则逆解每步的小残差会累积成“走不够”
        val (p, r) = toolTarget ?: ik.fk(setpoint)
        val dv = doubleArrayOf(toolVel[0] * dtS, toolVel[1] * dtS, toolVel[2] * dtS)
        val dp = if (toolFrame) Rot.mulV(r, dv) else dv
        val w = doubleArrayOf(toolVel[3] * dtS, toolVel[4] * dtS, toolVel[5] * dtS)
        val th = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
        val rn = if (th < 1e-12) r else {
            val dr = Rot.axisAngle(doubleArrayOf(w[0] / th, w[1] / th, w[2] / th), th)
            if (toolFrame) Rot.mul(r, dr) else Rot.mul(dr, r)
        }
        val tp = doubleArrayOf(p[0] + dp[0], p[1] + dp[1], p[2] + dp[2])
        val res = ik.solve(tp, rn, setpoint, maxIter = 30)
        if (res.posErr > 0.0015 || res.rotErr > Math.toRadians(1.5)) { toolBlocked = true; toolTarget = ik.fk(setpoint); return }
        toolBlocked = false
        val step = (0 until n).maxOf { abs(res.q[it] - setpoint[it]) }
        val limit = config.jogVelRad * 1.5 * dtS
        if (step > limit) {
            // 关节速度超限：按比例少走一点，目标拉回到实际能到的位置
            val k = limit / step
            setpoint = withEnvelope(clampAll(DoubleArray(n) { setpoint[it] + (res.q[it] - setpoint[it]) * k }))
            toolTarget = ik.fk(setpoint)
        } else {
            setpoint = withEnvelope(clampAll(res.q.copyOf()))
            toolTarget = tp to rn
        }
    }

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

    companion object {
        const val RECORD_PERIOD_S = 0.05
        /** 夹爪开合速度（全程/秒）。 */
        const val GRIPPER_SPEED = 1.0
    }
}
