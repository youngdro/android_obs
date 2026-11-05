package app.livecomposer.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 场景方案：保存“当前画面”的全部内容，之后一键切换。
 *
 * 包含：全部图层（位置、尺寸、层级、显隐、锁定、不透明度、音量、网页地址与视口、图片、文字、拉流地址等，
 * 以及横竖屏各自的布局）、画布方向与分辨率、帧率、码率、关键帧间隔、摄像头变焦与旋转校正、网页采集方式。
 * 不包含：推流地址和串流密钥（全局设置，且密钥只加密保存在一处）、麦克风静音状态。
 */
data class Scene(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val savedAt: Long,
    val data: JSONObject,
) {
    val orientation: CanvasOrientation get() = runCatching { CanvasOrientation.valueOf(data.optString("orientation")) }.getOrDefault(CanvasOrientation.LANDSCAPE)
    val resolution: Resolution get() = runCatching { Resolution.valueOf(data.optString("resolution")) }.getOrDefault(Resolution.P1080)
    val fps: Int get() = data.optInt("fps", 30)
    val layerTypes: List<LayerType>
        get() {
            val arr = data.optJSONArray("layers") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { runCatching { LayerType.valueOf(arr.getJSONObject(it).getString("type")) }.getOrNull() }
        }

    fun toJson() = JSONObject().apply { put("id", id); put("name", name); put("savedAt", savedAt); put("data", data) }

    companion object {
        fun fromJson(o: JSONObject) = Scene(o.getString("id"), o.optString("name", "场景"), o.optLong("savedAt"), o.getJSONObject("data"))
    }
}

/** 场景列表的持久化（SharedPreferences，与主配置分开存，避免主配置变大）。 */
class SceneStore(private val ctx: Context) {
    val scenes: MutableList<Scene> = load()
    var activeId: String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("active", null)
        set(value) { field = value; persist() }

    private fun load(): MutableList<Scene> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("scenes", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { runCatching { Scene.fromJson(arr.getJSONObject(it)) }.getOrNull() }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun persist() {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("scenes", JSONArray().apply { scenes.forEach { put(it.toJson()) } }.toString())
            .putString("active", activeId)
            .apply()
    }

    fun find(id: String) = scenes.firstOrNull { it.id == id }

    companion object {
        private const val PREFS = "scenes"
    }
}

/** 把当前配置中属于“画面”的部分序列化为场景数据。 */
fun AppConfig.toSceneData(): JSONObject = JSONObject().apply {
    put("orientation", orientation.name); put("resolution", resolution.name); put("fps", fps)
    put("videoBitrateKbps", videoBitrateKbps); put("keyFrameIntervalSec", keyFrameIntervalSec)
    put("zoom", zoom.toDouble()); put("webCaptureGpu", webCaptureGpu); put("allowCleartextWeb", allowCleartextWeb)
    put("cameraRotationFix", cameraRotationFix)
    put("layers", JSONArray().apply { layers.forEach { put(it.toJson()) } })
}

/** 场景里的图层（新对象，不与当前配置共享）。 */
fun sceneLayers(data: JSONObject): List<Layer> {
    val arr = data.optJSONArray("layers") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { runCatching { Layer.fromJson(arr.getJSONObject(it)) }.getOrNull() }
}
