package app.livecomposer.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 整体配置的导出 / 加载格式（文件和云端共用，见 docs/CONFIG_FORMAT.md）。
 *
 * ```
 * {
 *   "format": "livecomposer-config", "version": 1,
 *   "exportedAt": 1793923200000, "app": { "version": "0.9.0" },
 *   "settings": { 画布、编码、推流、降噪、省电、第二路、截图通道等设置，以及当前图层 "layers": [...] },
 *   "secrets": { "fullUrl", "streamKey", "camUrl", "visionApiKey" },   // 可选
 *   "scenes": [ { "id", "name", "savedAt", "data": {...} } ],           // 可选
 *   "activeSceneId": "..."                                              // 可选
 * }
 * ```
 *
 * - 本机设备 ID 等只属于这台手机的值不导出、不加载。
 * - 没有 "secrets" 时保留本机已有的推流地址和密钥；某个键为空字符串表示清空。
 * - 纯 Kotlin + org.json，不依赖 Android，可在 JVM 上测试（verify/）。
 */
object ConfigBundle {
    const val FORMAT = "livecomposer-config"
    const val VERSION = 1
    /** 文件 / 云端响应大小上限。 */
    const val MAX_BYTES = 2 * 1024 * 1024
    val SECRET_KEYS = listOf("fullUrl", "streamKey", "camUrl", "visionApiKey")
    /** 只属于本机、不随配置迁移的设置。 */
    private val LOCAL_ONLY = setOf("deviceId", "cloudConfigUrl", "cloudConfigToken", "cloudLiveId")

    fun build(
        settings: JSONObject,
        secrets: Map<String, String>?,
        scenes: JSONArray,
        activeSceneId: String?,
        appVersion: String,
        now: Long,
    ): JSONObject = JSONObject().apply {
        put("format", FORMAT); put("version", VERSION)
        put("exportedAt", now)
        put("app", JSONObject().put("name", "LiveComposer").put("version", appVersion))
        put("settings", JSONObject(settings.toString()).apply { LOCAL_ONLY.forEach { remove(it) } })
        if (secrets != null) put("secrets", JSONObject().apply { SECRET_KEYS.forEach { k -> put(k, secrets[k] ?: "") } })
        put("scenes", JSONArray(scenes.toString()))
        if (activeSceneId != null) put("activeSceneId", activeSceneId)
    }

    class Parsed(
        val settings: JSONObject,
        /** null = 文件里没有密钥，保留本机的。 */
        val secrets: Map<String, String>?,
        /** null = 文件里没有场景，保留本机的。 */
        val scenes: JSONArray?,
        val activeSceneId: String?,
        val exportedAt: Long,
        val appVersion: String,
        val layerCount: Int,
        val warnings: List<String>,
    ) {
        val sceneCount get() = scenes?.length() ?: 0
    }

    /** 解析并校验；格式不对时抛 IllegalArgumentException（中文说明）。 */
    fun parse(text: String): Parsed {
        if (text.length > MAX_BYTES) throw IllegalArgumentException("配置太大（超过 2MB）")
        val root = try { JSONObject(text.trim().removePrefix("﻿")) } catch (_: Exception) {
            throw IllegalArgumentException("不是有效的 JSON 配置")
        }
        // 云端接口可能把配置包在 data 里：{ "ok": true, "data": { "format": ... } }
        val o = when {
            root.has("format") -> root
            root.optJSONObject("data")?.has("format") == true -> root.getJSONObject("data")
            root.optJSONObject("config")?.has("format") == true -> root.getJSONObject("config")
            else -> throw IllegalArgumentException("不是 LiveComposer 的配置（缺少 format 字段）")
        }
        if (o.optString("format") != FORMAT) throw IllegalArgumentException("不是 LiveComposer 的配置（format=${o.optString("format").take(40)}）")
        val ver = o.optInt("version", -1)
        if (ver < 1) throw IllegalArgumentException("配置缺少版本号")
        if (ver > VERSION) throw IllegalArgumentException("配置版本 $ver 比本 App 支持的新，请先升级 App")
        val settings = o.optJSONObject("settings") ?: throw IllegalArgumentException("配置缺少 settings")
        val warnings = mutableListOf<String>()

        // 设置：去掉本机专属项；图层逐个校验，坏的丢弃
        val s = JSONObject(settings.toString()).apply { LOCAL_ONLY.forEach { remove(it) } }
        var localRefs = 0
        var badLayers = 0
        var layerCount = 0
        if (s.has("layers")) {
            val (layers, bad, refs) = cleanLayers(s.optJSONArray("layers"))
            s.put("layers", layers); badLayers += bad; localRefs += refs; layerCount = layers.length()
        }

        val secrets = o.optJSONObject("secrets")?.let { so ->
            SECRET_KEYS.filter { so.has(it) }.associateWith { so.optString(it).trim() }
        }

        val scenes = o.optJSONArray("scenes")?.let { arr ->
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val sc = arr.optJSONObject(i)
                val data = sc?.optJSONObject("data")
                if (sc == null || data == null || sc.optString("id").isEmpty()) { warnings += "第 ${i + 1} 个场景格式不对，已跳过"; continue }
                val (layers, bad, refs) = cleanLayers(data.optJSONArray("layers"))
                badLayers += bad; localRefs += refs
                out.put(JSONObject(sc.toString()).put("data", JSONObject(data.toString()).put("layers", layers)))
            }
            out
        }
        if (badLayers > 0) warnings += "$badLayers 个图层格式不对或类型不支持，已跳过"
        if (localRefs > 0) warnings += "$localRefs 个图层用的是导出那台手机上的本地图片 / 视频，在这台手机上可能打不开，需要重新选择"
        if (secrets == null) warnings += "配置里没有推流地址和密钥，会保留这台手机已填的"
        val active = o.optString("activeSceneId").takeIf { id -> id.isNotEmpty() && scenes != null && (0 until scenes.length()).any { scenes.getJSONObject(it).optString("id") == id } }
        return Parsed(s, secrets, scenes, active, o.optLong("exportedAt", 0L),
            o.optJSONObject("app")?.optString("version").orEmpty(), layerCount, warnings)
    }

    private val LAYER_TYPES = setOf("CAMERA", "WEB", "MEDIA", "IMAGE", "TEXT")

    /** 返回（合法图层，丢弃数，本机文件引用数）。 */
    private fun cleanLayers(arr: JSONArray?): Triple<JSONArray, Int, Int> {
        val out = JSONArray()
        if (arr == null) return Triple(out, 0, 0)
        var bad = 0; var refs = 0
        for (i in 0 until arr.length()) {
            val l = arr.optJSONObject(i)
            if (l == null || l.optString("id").isEmpty() || l.optString("type") !in LAYER_TYPES) { bad++; continue }
            for (k in listOf("imageUri", "mediaUri")) {
                val u = l.optString(k)
                if (u.startsWith("content://") || (u.startsWith("file://") && !u.startsWith("file:///android_asset/"))) refs++
            }
            out.put(l)
        }
        return Triple(out, bad, refs)
    }
}
