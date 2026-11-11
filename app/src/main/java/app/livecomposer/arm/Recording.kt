package app.livecomposer.arm

/**
 * 一条连续轨迹：固定周期采样，每个采样 = 6 个关节角（弧度）+ 夹爪开合（0..1，没有夹爪时为 NaN）。
 */
class Recording(val name: String, val periodS: Double, val samples: List<DoubleArray>) {
    val durationS: Double get() = (samples.size - 1) * periodS

    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("name", name).put("periodS", periodS)
        .put("samples", org.json.JSONArray().apply {
            for (s in samples) put(org.json.JSONArray().apply { for (v in s) put(if (v.isNaN()) org.json.JSONObject.NULL else Math.round(v * 1e5) / 1e5) })
        })

    companion object {
        fun fromJson(o: org.json.JSONObject): Recording {
            val a = o.getJSONArray("samples")
            return Recording(o.getString("name"), o.getDouble("periodS"), (0 until a.length()).map { i ->
                val s = a.getJSONArray(i); DoubleArray(s.length()) { k -> if (s.isNull(k)) Double.NaN else s.getDouble(k) }
            })
        }

        /** 多条轨迹存成一个文件：{"format":"livecomposer-arm-recordings","version":1,"recordings":[...]}。 */
        fun listToJson(list: Collection<Recording>): org.json.JSONObject = org.json.JSONObject()
            .put("format", "livecomposer-arm-recordings").put("version", 1)
            .put("recordings", org.json.JSONArray().apply { for (r in list) put(r.toJson()) })

        fun listFromJson(o: org.json.JSONObject): List<Recording> {
            val a = o.optJSONArray("recordings") ?: return emptyList()
            return (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }
    }
}
