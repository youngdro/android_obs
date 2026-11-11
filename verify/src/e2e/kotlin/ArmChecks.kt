import app.livecomposer.arm.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * 模拟舵机总线：解析 STS 报文，模拟 7 个舵机（位置以固定最大速度追目标），应答读写。
 * 可注入“卡住”的关节和负载，用来检查保护逻辑。
 */
class SimBus(ids: List<Int>, start: Map<Int, Int>) : ArmLink {
    class Servo(var pos: Double, var goal: Int, var torque: Boolean = false, var limit: Int = 1000, var load: Int = 0, var stuckAt: Int? = null)
    val servos = ids.associateWith { Servo(start[it]!!.toDouble(), start[it]!!) }
    private var rx = ByteArray(0)
    var maxTicksPerS = 3000.0
    var syncWrites = 0

    fun step(dtS: Double) {
        for (s in servos.values) if (s.torque) {
            val d = s.goal - s.pos; val m = maxTicksPerS * dtS
            var np = s.pos + d.coerceIn(-m, m)
            s.stuckAt?.let { st -> if ((s.pos <= st && np > st) || (s.pos >= st && np < st)) np = st.toDouble() }
            s.pos = np
        }
    }

    override fun write(bytes: ByteArray) {
        val id = bytes[2].toInt() and 0xFF; val inst = bytes[4].toInt() and 0xFF
        val p = bytes.copyOfRange(5, bytes.size - 1)
        fun u16(o: Int) = (p[o].toInt() and 0xFF) or ((p[o + 1].toInt() and 0xFF) shl 8)
        when (inst) {
            StsProtocol.INST_SYNC_WRITE -> {
                syncWrites++
                val len = p[1].toInt(); var o = 2
                while (o < p.size) { val sid = p[o].toInt(); servos[sid]?.goal = u16(o + 1); o += 1 + len }
            }
            StsProtocol.INST_PING -> servos[id]?.let { reply(id, ByteArray(0)) }
            StsProtocol.INST_WRITE -> servos[id]?.let { s ->
                when (p[0].toInt()) {
                    StsProtocol.ADDR_TORQUE_ENABLE -> s.torque = p[1].toInt() == 1
                    StsProtocol.ADDR_TORQUE_LIMIT -> s.limit = u16(1)
                }
                reply(id, ByteArray(0))
            }
            StsProtocol.INST_READ -> servos[id]?.let { s ->
                val v = when (p[0].toInt()) {
                    StsProtocol.ADDR_PRESENT_POSITION -> s.pos.toInt()
                    StsProtocol.ADDR_PRESENT_LOAD -> (abs(s.load) and 0x3FF) or (if (s.load < 0) 0x400 else 0)
                    else -> 0
                }
                reply(id, byteArrayOf((v and 0xFF).toByte(), (v shr 8).toByte()).copyOf(p[1].toInt()))
            }
        }
    }
    private fun reply(id: Int, params: ByteArray) {
        val pkt = StsProtocol.packet(id, 0, params) // 应答格式同请求，第 5 字节为错误码 0
        rx += pkt
    }
    override fun read(timeoutMs: Int): ByteArray { val r = rx; rx = ByteArray(0); return r }
    override fun drain() { rx = ByteArray(0) }
    override fun close() {}
}

fun main() {
    var failed = 0
    fun check(name: String, ok: Boolean, detail: String = "", quiet: Boolean = false) {
        if (!quiet || !ok) println((if (ok) "PASS " else "FAIL ") + name + (if (detail.isNotEmpty()) "  ($detail)" else ""))
        if (!ok) failed++
    }

    // 1. 报文：与飞特官方 SDK 一致（ping id 1 = FF FF 01 02 01 FB）
    check("ping 报文校验和", StsProtocol.ping(1).contentEquals(byteArrayOf(-1, -1, 1, 2, 1, 0xFB.toByte())))
    val sw = StsProtocol.syncWrite(42, 6, listOf(1 to StsProtocol.goalData(2048), 2 to StsProtocol.goalData(1000)))
    check("同步写长度", sw.size == 5 + 2 + 2 * 7 + 1 && sw[2] == 0xFE.toByte() && sw[4] == 0x83.toByte())
    val echo = StsProtocol.read(3, 56, 2)
    val resp = StsProtocol.packet(3, 0, byteArrayOf(0x10, 0x08))
    val parsed = StsProtocol.parse(echo + resp, 3, echo)
    check("跳过回显解析应答", parsed != null && StsProtocol.u16(parsed.params[0], parsed.params[1]) == 0x0810)
    check("负载符号位", StsProtocol.signedLoad(0x400 or 300) == -300 && StsProtocol.signedLoad(250) == 250)

    // 2. 标定换算
    val cal = ArmCalibration.A003
    val j4 = cal.joints[3]
    check("tick↔rad 往返", (0..4095 step 97).all { j4.toTick(j4.toRad(it)) == it })
    check("方向符号", j4.toRad(j4.zeroTick + 1024) < 0 && cal.joints[0].toRad(cal.joints[0].zeroTick + 1024) > 0)
    check("零位 = 0 rad", cal.joints.all { it.toRad(it.zeroTick) == 0.0 })

    // 3. 正运动学：零位时末端（夹爪）朝下、在大臂伸出方向一侧
    val p0 = ArmKinematics.forward(DoubleArray(6))
    check("零位正运动学", abs(p0.p[2] - 0.2148) < 0.01, "末端 ${p0.p.joinToString { "%.3f".format(it) }}")
    // 保持俯仰：大臂、小臂变化后手腕补偿，末端 Z 轴方向不变（关节 4 为 0 时）
    val ref = doubleArrayOf(0.0, -0.3, -0.6, 0.0, 0.4, 0.0)
    val q = doubleArrayOf(0.0, -0.8, -0.2, 0.0, 0.0, 0.0); q[4] = ArmKinematics.wristForLevel(q, ref)
    val a = ArmKinematics.forward(ref).axis(2); val b = ArmKinematics.forward(q).axis(2)
    check("手腕补偿保持朝向", (0..2).all { abs(a[it] - b[it]) < 0.02 }, "${a.joinToString { "%.3f".format(it) }} vs ${b.joinToString { "%.3f".format(it) }}")

    // 4. 控制器 + 模拟总线
    val start = cal.joints.associate { it.servoId to it.zeroTick } + (7 to cal.gripper!!.closedTick)
    val sim = SimBus(cal.servoIds, start)
    val arm = ArmController(StsBus(sim, 5), cal, ArmController.Config(maxVelRad = 1.0, maxAccRad = 2.0))
    check("上力", arm.enable() && sim.servos.values.all { it.torque && it.limit == 400 })
    check("上力不跳动", sim.servos.all { (id, s) -> s.goal == start[id] })
    fun run(seconds: Double) { val dt = 0.02; repeat((seconds / dt).toInt()) { arm.tick(dt); sim.step(dt) } }

    val target = doubleArrayOf(0.5, -0.6, -1.0, 0.3, 0.8, -0.5)
    arm.moveTo(target, gripper = 0.5, durationS = 2.0)
    // 同步性：中途各关节完成的比例应一致
    run(1.0)
    val mid = cal.joints.mapIndexed { i, j -> (j.toRad(sim.servos[j.servoId]!!.pos.toInt())) / target[i] }
    check("多关节同步", mid.max() - mid.min() < 0.06, mid.joinToString { "%.2f".format(it) })
    run(1.5)
    val err = cal.joints.mapIndexed { i, j -> abs(j.toRad(sim.servos[j.servoId]!!.pos.toInt()) - target[i]) }.max()
    check("到达目标", err < 0.01 && arm.state.mode == ArmController.Mode.IDLE, "最大误差 ${"%.4f".format(err)} rad")
    check("夹爪半开", abs(sim.servos[7]!!.pos - cal.gripper!!.toTick(0.5)) < 3)

    // 限位：超出范围的目标被夹住
    arm.moveTo(doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 0.0), durationS = 1.0)
    run(2.0)
    check("限位夹住", sim.servos[2]!!.pos.toInt() <= cal.joints[1].maxTick, "ID2 = ${sim.servos[2]!!.pos.toInt()}")

    // 遥控：持续发送时移动，停止发送 0.3 秒后自动停
    val before = sim.servos[1]!!.pos
    repeat(25) { arm.jog(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)); arm.tick(0.02); sim.step(0.02) }
    val moved = sim.servos[1]!!.pos - before
    run(1.0)
    val after = sim.servos[1]!!.pos
    run(1.0)
    check("遥控移动", moved > 50, "0.5 秒移动 ${moved.toInt()} tick")
    check("遥控超时自动停", abs(sim.servos[1]!!.pos - after) < 1 && arm.state.mode == ArmController.Mode.IDLE)

    // 负载超限：急停并原地保持（不卸力）
    sim.servos[3]!!.load = 600
    arm.moveTo(DoubleArray(6), durationS = 2.0)
    run(0.5)
    check("负载超限急停", arm.state.mode == ArmController.Mode.FAULT && sim.servos.values.all { it.torque }, arm.state.fault ?: "")
    sim.servos[3]!!.load = 0
    arm.clearFault()

    // 卡住：跟随误差持续过大
    sim.servos[5]!!.stuckAt = sim.servos[5]!!.pos.toInt() - 10
    arm.moveTo(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 0.0), durationS = 1.0)
    run(3.0)
    check("卡住急停", arm.state.mode == ArmController.Mode.FAULT, arm.state.fault ?: "")
    sim.servos[5]!!.stuckAt = null; arm.clearFault()

    // 预设：两帧依次执行
    val preset = Preset("测试", listOf(Keyframe(doubleArrayOf(0.2, -0.2, -0.2, 0.0, 0.2, 0.0), 1.0, 1.0, 0.2), Keyframe(DoubleArray(6), 0.0, 1.0)))
    arm.play(preset); run(1.1)
    val mid1 = cal.joints[0].toRad(sim.servos[1]!!.pos.toInt())
    run(1.5)
    check("预设依次执行", abs(mid1 - 0.2) < 0.02 && abs(cal.joints[0].toRad(sim.servos[1]!!.pos.toInt())) < 0.01, "第一帧 ${"%.3f".format(mid1)}")

    arm.disable()
    check("卸力", sim.servos.values.none { it.torque })

    // 5. 大臂/手腕避让（arm2 实测包络；大臂限位放宽到 ~−90° 以覆盖包络的突变段）
    val env = WristEnvelope.A003_ARM2
    fun pose(j2: Double, j3: Double) = doubleArrayOf(0.0, Math.toRadians(j2), Math.toRadians(j3), 0.0, 0.0, 0.0)
    check("包络插值", env.minWrist(pose(0.0, -40.0)) == null && abs(Math.toDegrees(env.minWrist(pose(-30.9, -40.0))!!) - 15.0) < 0.01 &&
        abs(Math.toDegrees(env.minWrist(pose(-150.0, -40.0))!!) - 60.9) < 0.01 && abs(Math.toDegrees(env.minWrist(pose(0.0, 0.0))!!) - 10.0) < 0.01)
    check("包络 JSON 往返", ArmCalibration.fromJson(ArmCalibration.A003_ARM2.toJson()).wristEnvelope?.minWrist(pose(-35.0, -5.0))?.let { abs(it - env.minWrist(pose(-35.0, -5.0))!!) < 1e-9 } == true)
    val cal2 = ArmCalibration.A003_ARM2.let { c -> c.copy(joints = c.joints.mapIndexed { i, j -> if (i == 1) j.copy(minTick = 2000) else j }) }
    // 实机收拢时手腕在零位附近；这里从手腕已让开 10° 的“回零”姿态开始
    val start2 = cal2.joints.associate { it.servoId to (if (it.servoId == 5) it.toTick(Math.toRadians(10.0)) else it.zeroTick) } + (7 to cal2.gripper!!.closedTick)
    val sim2 = SimBus(cal2.servoIds, start2)
    val arm2 = ArmController(StsBus(sim2, 5), cal2, ArmController.Config(maxVelRad = 1.0, maxAccRad = 2.0))
    arm2.enable()
    fun q2(): DoubleArray = cal2.joints.map { it.toRad(sim2.servos[it.servoId]!!.pos.toInt()) }.toDoubleArray()
    var worstGap = 0.0
    var j2MovedBeforeClear = false
    fun run2(seconds: Double) {
        val dt = 0.02
        repeat((seconds / dt).toInt()) {
            arm2.tick(dt); sim2.step(dt)
            val q = q2(); val m = env.minWrist(q) ?: return@repeat
            worstGap = maxOf(worstGap, m - q[4])
        }
    }
    val lift = doubleArrayOf(0.0, Math.toRadians(-60.0), 0.0, 0.0, 0.0, 0.0)
    arm2.moveTo(lift, durationS = 2.0)
    run2(0.6)
    val early = q2()
    j2MovedBeforeClear = early[1] < Math.toRadians(-2.0) && early[4] < Math.toRadians(40.0)
    run2(5.0)
    val end2 = q2()
    check("抬大臂前手腕先让开", !j2MovedBeforeClear, "0.6 秒时 J2 ${"%.1f".format(Math.toDegrees(early[1]))}° J5 ${"%.1f".format(Math.toDegrees(early[4]))}°")
    check("抬大臂全程手腕不进包络", worstGap < Math.toRadians(1.0), "最大侵入 ${"%.2f".format(Math.toDegrees(worstGap))}°")
    check("大臂到位、手腕停在包络下限", abs(end2[1] - lift[1]) < 0.01 && abs(end2[4] - env.minWrist(lift)!!) < 0.02 &&
        arm2.state.mode == ArmController.Mode.IDLE, "J2 ${"%.1f".format(Math.toDegrees(end2[1]))}° J5 ${"%.1f".format(Math.toDegrees(end2[4]))}°")
    arm2.moveTo(DoubleArray(6), durationS = 2.0); run2(6.0)
    check("回零位：手腕停在小臂收拢需要的 10°", q2().withIndex().all { (i, v) -> abs(v - (if (i == 4) Math.toRadians(10.0) else 0.0)) < 0.01 } && worstGap < Math.toRadians(1.0),
        q2().joinToString { "%.1f".format(Math.toDegrees(it)) })
    // 小臂收拢：手腕在 0 时把小臂从 −60° 收回，手腕先让开
    arm2.moveTo(doubleArrayOf(0.0, 0.0, Math.toRadians(-60.0), 0.0, 0.0, 0.0), durationS = 2.0); run2(4.0)
    check("小臂抬起后手腕可以回 0", abs(q2()[4]) < 0.01 && abs(q2()[2] - Math.toRadians(-60.0)) < 0.01)
    worstGap = 0.0
    arm2.moveTo(DoubleArray(6), durationS = 2.0); run2(5.0)
    check("小臂收拢全程手腕不进包络", worstGap < Math.toRadians(1.0) && abs(q2()[2]) < 0.01, "最大侵入 ${"%.2f".format(Math.toDegrees(worstGap))}°")
    // 遥控：手腕在 0 时往上推大臂，手腕自动转开，大臂等手腕
    worstGap = 0.0
    repeat(150) { arm2.jog(doubleArrayOf(0.0, -1.0, 0.0, 0.0, 0.0, 0.0)); arm2.tick(0.02); sim2.step(0.02)
        val q = q2(); env.minWrist(q)?.let { worstGap = maxOf(worstGap, it - q[4]) } }
    check("遥控抬大臂手腕自动让开", worstGap < Math.toRadians(1.0) && q2()[1] < Math.toRadians(-30.0),
        "J2 ${"%.1f".format(Math.toDegrees(q2()[1]))}° J5 ${"%.1f".format(Math.toDegrees(q2()[4]))}° 侵入 ${"%.2f".format(Math.toDegrees(worstGap))}°")
    run2(0.5)
    repeat(50) { arm2.jog(doubleArrayOf(0.0, 0.0, 0.0, 0.0, -1.0, 0.0)); arm2.tick(0.02); sim2.step(0.02) }
    check("遥控手腕不能压进包络", q2()[4] >= env.minWrist(q2())!! - 0.01)
    arm2.disable()

    // 6. 逆运动学：随机取可达的关节角 → 正解得到夹爪位姿 → 从回零姿态求逆解，应能回到同一位姿
    val ik = ArmIk(cal2)
    val rnd = java.util.Random(7)
    val home = ik.project(DoubleArray(6))
    var ok = 0; var total = 0; var worstMs = 0L; var sumMs = 0L
    val misses = mutableListOf<String>()
    repeat(200) {
        val q = ik.project(DoubleArray(6) { i -> val j = cal2.joints[i]; j.minRad + (j.maxRad - j.minRad) * rnd.nextDouble() })
        val target = ik.forward(q)
        val t0 = System.nanoTime()
        val r = ik.solveBest(target, home)
        val ms = (System.nanoTime() - t0) / 1_000_000; worstMs = maxOf(worstMs, ms); sumMs += ms
        total++
        if (r.reached) ok++ else if (misses.size < 3) misses += "pos ${"%.1f".format(r.posErr * 1000)}mm rot ${"%.1f".format(Math.toDegrees(r.rotErr))}°"
        // 解出的关节角必须在限位内、手腕不进包络
        check("逆解在限位内 #$it", r.q.withIndex().all { (i, v) -> v >= cal2.joints[i].minRad - 1e-9 && v <= cal2.joints[i].maxRad + 1e-9 } &&
            (env.minWrist(r.q)?.let { r.q[4] >= it - 1e-9 } ?: true), quiet = true)
    }
    check("逆解成功率", ok >= total * 0.95, "$ok/$total，平均 ${sumMs / total} ms，最慢 $worstMs ms ${misses.joinToString()}")
    // 正解/逆解往返：ToolPose 欧拉角 ↔ 旋转矩阵
    val tp = ToolPose(0.1, 0.05, 0.2, 0.3, -0.7, 0.4)
    check("位姿欧拉角往返", ToolPose.of(tp.p, tp.r()).let { abs(it.yaw - tp.yaw) + abs(it.pitch - tp.pitch) + abs(it.roll - tp.roll) < 1e-9 })
    val zeroTool = ik.forward(DoubleArray(6))
    check("零位夹爪朝下", Math.toDegrees(zeroTool.pitch) < -80, "零位夹爪 ${"%.0f".format(zeroTool.x * 1000)} ${"%.0f".format(zeroTool.y * 1000)} ${"%.0f".format(zeroTool.z * 1000)} mm，仰角 ${"%.0f".format(Math.toDegrees(zeroTool.pitch))}°")

    // 7. 控制器：空间移动、空间点动、录制回放（arm2 标定 + 模拟总线）
    // 先从“手腕在 0°、小臂收拢”（在包络里）上力后直接空间点动：应先转出手腕，再往上走
    run {
        val simH = SimBus(cal2.servoIds, cal2.joints.associate { it.servoId to it.zeroTick } + (7 to cal2.gripper!!.closedTick))
        val armH = ArmController(StsBus(simH, 5), cal2)
        armH.enable()
        val z0 = armH.ik.fk(armH.state.setpoint).first[2]
        repeat(100) { armH.jogTool(doubleArrayOf(0.0, 0.0, 0.03, 0.0, 0.0, 0.0)); armH.tick(0.02); simH.step(0.02) }
        val z1 = armH.ik.fk(armH.state.setpoint).first[2]
        check("零位上力后直接空间点动", z1 - z0 > 0.03 && !armH.state.toolBlocked, "升高 ${"%.0f".format((z1 - z0) * 1000)} mm")
    }
    val sim3 = SimBus(cal2.servoIds, start2)
    val arm3 = ArmController(StsBus(sim3, 5), cal2)
    arm3.enable()
    fun run3(seconds: Double) { val dt = 0.02; repeat((seconds / dt).toInt()) { arm3.tick(dt); sim3.step(dt) } }
    fun meas3() = cal2.joints.map { it.toRad(sim3.servos[it.servoId]!!.pos.toInt()) }.toDoubleArray()
    val goalQ = ik.project(doubleArrayOf(0.4, Math.toRadians(-60.0), Math.toRadians(-100.0), 0.2, Math.toRadians(70.0), 0.3))
    val goalPose = ik.forward(goalQ)
    val res3 = arm3.moveToPose(goalPose)
    run3(15.0)
    val (pNow, rNow) = ik.fk(meas3())
    val posErr3 = sqrt((0..2).sumOf { (pNow[it] - goalPose.p[it]).let { d -> d * d } })
    check("移动到空间位姿", res3?.reached == true && posErr3 < 0.003 && arm3.state.mode == ArmController.Mode.IDLE,
        "实际误差 ${"%.1f".format(posErr3 * 1000)} mm，模式 ${arm3.state.mode}")
    // 空间点动：底座坐标系向上 3 cm/s 走 1 秒，朝向和水平位置不变
    val (p0j, r0j) = ik.fk(arm3.state.setpoint)
    repeat(50) { arm3.jogTool(doubleArrayOf(0.0, 0.0, 0.03, 0.0, 0.0, 0.0)); arm3.tick(0.02); sim3.step(0.02) }
    val (p1j, r1j) = ik.fk(arm3.state.setpoint)
    val rotDrift = Rot.errorVec(r0j, r1j).let { sqrt(it.sumOf { v -> v * v }) }
    check("空间点动沿 Z 上升", abs(p1j[2] - p0j[2] - 0.03) < 0.004 && abs(p1j[0] - p0j[0]) < 0.002 && abs(p1j[1] - p0j[1]) < 0.002 && rotDrift < Math.toRadians(1.0),
        "Δz ${"%.1f".format((p1j[2] - p0j[2]) * 1000)} mm，Δx ${"%.1f".format((p1j[0] - p0j[0]) * 1000)}，Δy ${"%.1f".format((p1j[1] - p0j[1]) * 1000)}，朝向漂移 ${"%.2f".format(Math.toDegrees(rotDrift))}°，被挡 ${arm3.state.toolBlocked}")
    // 夹爪坐标系：沿指向前伸 2 cm
    val (p2j, r2j) = ik.fk(arm3.state.setpoint)
    repeat(50) { arm3.jogTool(doubleArrayOf(0.02, 0.0, 0.0, 0.0, 0.0, 0.0), inToolFrame = true); arm3.tick(0.02); sim3.step(0.02) }
    val (p3jf, _) = ik.fk(arm3.state.setpoint)
    val along = (0..2).sumOf { (p3jf[it] - p2j[it]) * r2j[it * 3] }
    check("空间点动沿夹爪指向前伸", abs(along - 0.02) < 0.004, "前伸 ${"%.1f".format(along * 1000)} mm")
    // 绕竖直轴转 0.15 rad/s 一秒：位置不变。先回到工作空间中部（靠近底座轴线时转向需要的关节速度会放大很多倍，会被自动放慢）
    arm3.moveTo(goalQ); run3(10.0)
    val (p3j, r2j0) = ik.fk(arm3.state.setpoint)
    var blockedTicks = 0
    repeat(50) { arm3.jogTool(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.15)); arm3.tick(0.02); sim3.step(0.02); if (arm3.state.toolBlocked) blockedTicks++ }
    val (p4j, r4j) = ik.fk(arm3.state.setpoint)
    val turned = Rot.errorVec(r2j0, r4j)[2]
    check("空间点动原地转向", sqrt((0..2).sumOf { (p4j[it] - p3j[it]).let { d -> d * d } }) < 0.003 && abs(turned - 0.15) < 0.03,
        "转了 ${"%.1f".format(Math.toDegrees(turned))}°，被挡 ${arm3.state.toolBlocked}，关节 ${arm3.state.setpoint.joinToString { "%.0f".format(Math.toDegrees(it)) }}")
    // 点动到工作空间外：一直往外推，被挡住但不报错、不越限
    repeat(400) { arm3.jogTool(doubleArrayOf(0.1, 0.0, 0.0, 0.0, 0.0, 0.0), inToolFrame = true); arm3.tick(0.02); sim3.step(0.02) }
    check("点动到边界被挡住", arm3.state.toolBlocked && arm3.state.mode != ArmController.Mode.FAULT &&
        arm3.state.setpoint.withIndex().all { (i, v) -> v >= cal2.joints[i].minRad - 1e-9 && v <= cal2.joints[i].maxRad + 1e-9 })
    run3(1.0)
    // 录制：上力时界面操控（关节点动 + 夹爪），结束后回放，终点一致
    arm3.moveTo(home); run3(15.0)
    arm3.startRecording()
    repeat(60) { arm3.jog(doubleArrayOf(0.5, -0.5, 0.0, 0.0, 0.0, 0.0)); arm3.tick(0.02); sim3.step(0.02) }
    arm3.setGripper(0.8)
    repeat(60) { arm3.jogTool(doubleArrayOf(0.0, 0.0, 0.02, 0.0, 0.0, 0.0)); arm3.tick(0.02); sim3.step(0.02) }
    run3(0.5)
    val recEnd = arm3.state.setpoint.copyOf()
    val rec = arm3.stopRecording("测试")
    check("录制得到轨迹", rec != null && rec.samples.size > 40 && abs(rec.durationS - 2.9) < 0.3, "${rec?.samples?.size} 个采样，${"%.1f".format(rec?.durationS)} 秒")
    val recJson = Recording.listFromJson(Recording.listToJson(listOf(rec!!))).single()
    check("轨迹 JSON 往返", recJson.samples.size == rec.samples.size && abs(recJson.samples.last()[0] - rec.samples.last()[0]) < 1e-4)
    arm3.moveTo(home); run3(15.0)
    arm3.playRecording(recJson)
    var sawPlayback = false
    repeat(1500) { arm3.tick(0.02); sim3.step(0.02); if (arm3.state.mode == ArmController.Mode.PLAYBACK) sawPlayback = true }
    val endErr = (0 until 6).maxOf { abs(meas3()[it] - recEnd[it]) }
    check("回放到达录制终点", sawPlayback && endErr < 0.01 && arm3.state.mode == ArmController.Mode.IDLE && abs((arm3.state.gripper ?: 0.0) - 0.8) < 0.02,
        "终点误差 ${"%.4f".format(endErr)} rad，夹爪 ${arm3.state.gripper}")
    // 回放时保持夹持：夹爪停在当前值，不跟录制走
    arm3.setGripper(0.3); run3(2.0)
    arm3.playRecording(recJson, holdGripper = true)
    repeat(1500) { arm3.tick(0.02); sim3.step(0.02) }
    check("回放时保持夹持", abs((arm3.state.gripper ?: 0.0) - 0.3) < 0.02, "夹爪 ${arm3.state.gripper}")
    // 卸力手掰录制：模拟用手把底座转 30°、大臂抬 20°
    arm3.moveTo(home); run3(15.0)
    arm3.disable()
    arm3.startRecording()
    val s1 = sim3.servos[1]!!; val s2 = sim3.servos[2]!!
    val t1 = cal2.joints[0].toTick(Math.toRadians(30.0)); val t2 = cal2.joints[1].toTick(Math.toRadians(-20.0))
    val a1 = s1.pos; val a2 = s2.pos
    repeat(100) { k -> s1.pos = a1 + (t1 - a1) * (k + 1) / 100; s2.pos = a2 + (t2 - a2) * (k + 1) / 100; arm3.tick(0.02) }
    val handRec = arm3.stopRecording("手掰")
    check("卸力手掰录制", handRec != null && abs(handRec.samples.last()[0] - Math.toRadians(30.0)) < 0.01 && abs(handRec.samples.last()[1] - Math.toRadians(-20.0)) < 0.01,
        "${handRec?.samples?.size} 个采样")
    // 手掰的末端可能进包络（大臂 −20° 时手腕要 ≥ 7.8°），回放时自动抬手腕
    for (sv in sim3.servos.values) sv.goal = sv.pos.toInt()
    arm3.enable(); arm3.moveTo(home); run3(15.0)
    arm3.playRecording(handRec!!); run3(20.0)
    val m3 = meas3()
    check("手掰轨迹回放并套用包络", abs(m3[0] - Math.toRadians(30.0)) < 0.02 && m3[4] >= env.minWrist(m3)!! - 0.01,
        "底座 ${"%.1f".format(Math.toDegrees(m3[0]))}° 手腕 ${"%.1f".format(Math.toDegrees(m3[4]))}°")

    // 快速手掰（底座 1 秒转 1.5 rad）按原速回放，2 倍速时间减半
    arm3.moveTo(home); run3(15.0)
    val fast = Recording("快", 0.05, (0..20).map { k -> home.copyOf().also { it[0] = 1.5 * k / 20 } + doubleArrayOf(Double.NaN) })
    fun playTime(speed: Double): Double {
        arm3.moveTo(home); run3(15.0)
        arm3.playRecording(fast, speed, maxVelRad = 4.0); var t = 0.0
        repeat(1500) { arm3.tick(0.02); sim3.step(0.02); if (arm3.state.mode == ArmController.Mode.PLAYBACK) t += 0.02 }
        return t
    }
    val pt1 = playTime(1.0); val pt2 = playTime(2.0)
    check("快速动作按原速回放", abs(pt1 - 1.0) < 0.1 && abs(pt2 - 0.5) < 0.1, "1 倍 ${"%.2f".format(pt1)} 秒，2 倍 ${"%.2f".format(pt2)} 秒")

    // 握持：慢慢合上，碰到东西（负载升高）就锁定；锁定后回放、卸力都不松开
    val gc = cal2.gripper!!; val gs = sim3.servos[gc.servoId]!!
    arm3.moveTo(home); run3(15.0)
    arm3.setGripper(0.7); run3(2.0)
    val stuck = gc.toTick(0.3); gs.stuckAt = stuck
    arm3.grasp()
    repeat(400) { arm3.tick(0.02); sim3.step(0.02); if (gs.pos.toInt() == stuck) gs.load = 300 }
    check("握持夹住后锁定", arm3.state.gripperLocked && !arm3.state.grasping && abs(gc.toOpen(gs.pos.toInt()) - 0.3) < 0.02,
        "锁定 ${arm3.state.gripperLocked} 开合 ${"%.2f".format(gc.toOpen(gs.pos.toInt()))}")
    arm3.setGripper(1.0); run3(1.0)
    arm3.playRecording(recJson); repeat(1500) { arm3.tick(0.02); sim3.step(0.02) }
    check("锁定时滑条和回放都不动夹爪", abs(gc.toOpen(gs.pos.toInt()) - 0.3) < 0.02 && abs(gc.toOpen(gs.goal) - 0.20) < 0.02,
        "开合 ${"%.2f".format(gc.toOpen(gs.pos.toInt()))} 目标 ${"%.2f".format(gc.toOpen(gs.goal))}")
    arm3.moveTo(home); run3(15.0)
    arm3.disable()
    check("锁定时卸力保持夹爪上力", gs.torque && sim3.servos.filterKeys { it != gc.servoId }.values.none { it.torque })
    val arm4 = ArmController(StsBus(sim3, 5), cal2); arm4.carryGripFrom(arm3)
    for (sv in sim3.servos.values) sv.goal = sv.pos.toInt()
    arm4.enable(); arm4.tick(0.02)
    check("换控制器后握持锁定保留", arm4.state.gripperLocked && abs(gc.toOpen(gs.goal) - 0.20) < 0.02, "目标 ${"%.2f".format(gc.toOpen(gs.goal))}")
    arm4.setGripSqueeze(0.25); arm4.tick(0.02)
    check("锁定中调大压紧量", abs(gc.toOpen(gs.goal) - 0.05) < 0.02, "目标 ${"%.2f".format(gc.toOpen(gs.goal))}")
    arm4.releaseGrasp(); arm4.setGripper(0.8); repeat(100) { arm4.tick(0.02); sim3.step(0.02) }
    gs.stuckAt = null; gs.load = 0; repeat(100) { arm4.tick(0.02); sim3.step(0.02) }
    check("解除锁定后夹爪可以松开", !arm4.state.gripperLocked && abs(gc.toOpen(gs.pos.toInt()) - 0.8) < 0.03, "开合 ${"%.2f".format(gc.toOpen(gs.pos.toInt()))}")
    arm4.disable()

    println(if (failed == 0) "全部通过" else "$failed 项失败")
    exitProcess(if (failed == 0) 0 else 1)
}
