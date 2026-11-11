package app.livecomposer.arm

/**
 * 大臂（关节 2）与手腕（关节 5）的碰撞包络：大臂抬到某个角度时，手腕至少要转到多少才不会被打印件挤压。
 * 由实机数据得到（手腕卸力被动推开的接触点 + 5° 余量；更高处取人工演示时手腕保持的位置）。
 *
 * 点按关节 2 的角度从 0 往抬起方向（负）排列，中间线性插值；超出最后一点按最后一点。角度为 URDF 弧度。
 */
class WristEnvelope(points: List<Pair<Double, Double>>) {
    private val pts = points.sortedByDescending { it.first }

    /** 关节 2 在 [q2] 时手腕的最小角度；没有约束时返回 null。 */
    fun minWrist(q2: Double): Double? {
        if (pts.isEmpty() || q2 >= pts.first().first) return null
        for (k in 1 until pts.size) {
            val (a2, a5) = pts[k - 1]; val (b2, b5) = pts[k]
            if (q2 >= b2) return a5 + (b5 - a5) * (q2 - a2) / (b2 - a2)
        }
        return pts.last().second
    }

    /** 关节 2 沿直线从 [from] 走到 [to] 的过程中，手腕需要的最大值。 */
    fun maxAlong(from: Double, to: Double, steps: Int = 50): Double? {
        var best: Double? = null
        for (i in 0..steps) {
            val m = minWrist(from + (to - from) * i / steps) ?: continue
            if (best == null || m > best) best = m
        }
        return best
    }

    fun toJson(): org.json.JSONArray = org.json.JSONArray().apply {
        for ((q2, q5) in pts) put(org.json.JSONObject().put("j2Deg", Math.toDegrees(q2)).put("j5MinDeg", Math.toDegrees(q5)))
    }

    companion object {
        fun fromJson(a: org.json.JSONArray) = WristEnvelope((0 until a.length()).map {
            val o = a.getJSONObject(it); Math.toRadians(o.getDouble("j2Deg")) to Math.toRadians(o.getDouble("j5MinDeg"))
        })

        private fun deg(vararg p: Double) = (p.indices step 2).map { Math.toRadians(p[it]) to Math.toRadians(p[it + 1]) }

        /**
         * 第二台 a003（arm2）2026-11-11 实测。−10° 以内无约束；−47.6° 以内是接触点 + 5°；
         * 更高处是演示中手腕保持的值（偏保守）。在 −10° 处从 0 起步，避免 −10° 到 −15° 之间手腕突跳。
         */
        val A003_ARM2 = WristEnvelope(deg(
            -10.0, 0.0, -15.3, 6.9, -20.2, 7.8, -23.9, 11.3, -28.2, 13.7, -30.9, 15.0, -35.4, 15.4,
            -38.8, 16.2, -43.2, 18.3, -47.6, 20.2, -51.0, 48.6, -75.0, 49.7, -78.0, 55.7, -84.0, 60.3, -135.0, 60.9,
        ))
    }
}
