package app.livecomposer.arm

/**
 * 手腕（关节 5）的碰撞包络：大臂、小臂在某些角度时，手腕至少要转到多少才不会被打印件挤压。
 * 由实机数据得到。每条规则是“某个关节的角度 → 手腕下限”的折线，几条规则取最大值。
 * 角度为 URDF 弧度，关节下标从 0 开始（关节 2 = 下标 1）。
 */
class WristEnvelope(val rules: List<Rule>) {
    /** [points] 为（关节角，手腕下限）；在第一个点和最后一个点之间线性插值，范围外不约束。 */
    class Rule(val joint: Int, points: List<Pair<Double, Double>>) {
        val pts = points.sortedBy { it.first }

        fun at(x: Double): Double? {
            if (pts.isEmpty() || x < pts.first().first || x > pts.last().first) return null
            for (k in 1 until pts.size) {
                val (a, av) = pts[k - 1]; val (b, bv) = pts[k]
                if (x <= b) return if (b == a) max(av, bv) else av + (bv - av) * (x - a) / (b - a)
            }
            return pts.last().second
        }

        private fun max(a: Double, b: Double) = if (a > b) a else b
    }

    /** 姿态 [q] 下手腕的最小角度；没有约束时返回 null。 */
    fun minWrist(q: DoubleArray): Double? {
        var best: Double? = null
        for (r in rules) { val m = r.at(q[r.joint]) ?: continue; if (best == null || m > best) best = m }
        return best
    }

    /** 各关节沿直线（同步轨迹）从 [from] 走到 [to] 的过程中，手腕需要的最大值。 */
    fun maxAlong(from: DoubleArray, to: DoubleArray, steps: Int = 50): Double? {
        var best: Double? = null
        val q = from.copyOf()
        for (i in 0..steps) {
            val s = i.toDouble() / steps
            for (j in q.indices) q[j] = from[j] + (to[j] - from[j]) * s
            val m = minWrist(q) ?: continue
            if (best == null || m > best) best = m
        }
        return best
    }

    fun toJson(): org.json.JSONArray = org.json.JSONArray().apply {
        for (r in rules) put(org.json.JSONObject().put("joint", r.joint + 1).put("points", org.json.JSONArray().apply {
            for ((x, m) in r.pts) put(org.json.JSONArray().put(Math.toDegrees(x)).put(Math.toDegrees(m)))
        }))
    }

    companion object {
        fun fromJson(a: org.json.JSONArray) = WristEnvelope((0 until a.length()).map { i ->
            val o = a.getJSONObject(i); val p = o.getJSONArray("points")
            Rule(o.getInt("joint") - 1, (0 until p.length()).map { k ->
                val e = p.getJSONArray(k); Math.toRadians(e.getDouble(0)) to Math.toRadians(e.getDouble(1))
            })
        })

        private fun deg(vararg p: Double) = (p.indices step 2).map { Math.toRadians(p[it]) to Math.toRadians(p[it + 1]) }

        /**
         * 第二台 a003（arm2）2026-11-11 实测。
         * 大臂（关节 2，抬起为负）：−10° 以内无约束；−47.6° 以内是接触点 + 5°；更高处是演示中手腕保持的值（偏保守）。
         * 小臂（关节 3，抬起为负）：收拢时手腕只需转开约 2° 就能让过（实测），按 10° 留余量，收拢前 12° 内生效。
         */
        val A003_ARM2 = WristEnvelope(listOf(
            Rule(1, deg(
                -180.0, 60.9, -135.0, 60.9, -84.0, 60.3, -78.0, 55.7, -75.0, 49.7, -51.0, 48.6, -47.6, 20.2, -43.2, 18.3,
                -38.8, 16.2, -35.4, 15.4, -30.9, 15.0, -28.2, 13.7, -23.9, 11.3, -20.2, 7.8, -15.3, 6.9, -10.0, 0.0,
            )),
            Rule(2, deg(-20.0, 0.0, -12.0, 10.0, 5.0, 10.0)),
        ))
    }
}
