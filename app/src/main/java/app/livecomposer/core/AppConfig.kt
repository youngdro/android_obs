package app.livecomposer.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class CanvasOrientation(val label: String) { LANDSCAPE("横屏 16:9"), PORTRAIT("竖屏 9:16") }

enum class Resolution(val label: String, val long: Int, val short: Int) {
    P720("720p", 1280, 720), P1080("1080p", 1920, 1080)
}

enum class LayerType(val label: String) {
    CAMERA("摄像头"), WEB("网页"), MEDIA("媒体"), IMAGE("图片"), TEXT("文字");

    /** 有声音、可调音量的图层。 */
    val hasAudio get() = this == CAMERA || this == MEDIA || this == WEB
}

/** 图层。位置/尺寸为画布像素坐标（左上角原点）。网页图层的渲染视口（CSS 像素）与其在画布中的显示尺寸相互独立。 */
data class Layer(
    val id: String = UUID.randomUUID().toString(),
    val type: LayerType,
    var name: String,
    var x: Float, var y: Float, var w: Float, var h: Float,
    var visible: Boolean = true,
    var locked: Boolean = false,
    var opacity: Float = 1f,
    // 网页
    var url: String = "",
    var viewportW: Int = 1920,
    var viewportH: Int = 1080,
    // 图片
    var imageUri: String = "",
    // 文字
    var text: String = "",
    var textSizePx: Float = 64f,
    var textColor: Int = 0xFFFFFFFF.toInt(),
    // 媒体（拉流地址或本地视频）
    var mediaUri: String = "",
    /** true = 拉流地址（直播流，断线自动重连）；false = 本地视频文件（循环播放）。 */
    var mediaLive: Boolean = true,
    var loop: Boolean = true,
    // 声音：摄像头图层 = 麦克风音量；媒体图层 = 该源音量。0..2（1 = 原始音量）
    var volume: Float = 1f,
    /** 各画布方向下的布局（x, y, w, h 归一化到画布尺寸），键为 CanvasOrientation.name。 */
    val layouts: MutableMap<String, FloatArray> = mutableMapOf(),
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("type", type.name); put("name", name)
        put("x", x.toDouble()); put("y", y.toDouble()); put("w", w.toDouble()); put("h", h.toDouble())
        put("visible", visible); put("locked", locked); put("opacity", opacity.toDouble())
        put("url", url); put("viewportW", viewportW); put("viewportH", viewportH)
        put("imageUri", imageUri); put("text", text); put("textSizePx", textSizePx.toDouble()); put("textColor", textColor)
        put("mediaUri", mediaUri); put("mediaLive", mediaLive); put("loop", loop); put("volume", volume.toDouble())
        put("layouts", JSONObject().apply {
            layouts.forEach { (k, v) -> put(k, JSONArray().apply { v.forEach { f -> put(f.toDouble()) } }) }
        })
    }

    companion object {
        fun fromJson(o: JSONObject) = Layer(
            id = o.getString("id"), type = LayerType.valueOf(o.getString("type")), name = o.optString("name"),
            x = o.optDouble("x").toFloat(), y = o.optDouble("y").toFloat(),
            w = o.optDouble("w").toFloat(), h = o.optDouble("h").toFloat(),
            visible = o.optBoolean("visible", true), locked = o.optBoolean("locked"),
            opacity = o.optDouble("opacity", 1.0).toFloat(),
            url = o.optString("url"), viewportW = o.optInt("viewportW", 1920), viewportH = o.optInt("viewportH", 1080),
            imageUri = o.optString("imageUri"), text = o.optString("text"),
            textSizePx = o.optDouble("textSizePx", 64.0).toFloat(), textColor = o.optInt("textColor", -1),
            mediaUri = o.optString("mediaUri"), mediaLive = o.optBoolean("mediaLive", true),
            loop = o.optBoolean("loop", true), volume = o.optDouble("volume", 1.0).toFloat(),
            layouts = mutableMapOf<String, FloatArray>().apply {
                val lo = o.optJSONObject("layouts") ?: return@apply
                for (k in lo.keys()) {
                    val a = lo.optJSONArray(k) ?: continue
                    if (a.length() == 4) put(k, FloatArray(4) { a.getDouble(it).toFloat() })
                }
            },
        )
    }
}

/** 全部可保存配置。推流密钥单独加密保存（Android Keystore AES-GCM），不进入普通配置和日志。 */
data class AppConfig(
    var orientation: CanvasOrientation = CanvasOrientation.LANDSCAPE,
    var resolution: Resolution = Resolution.P1080,
    var fps: Int = 30,
    var videoBitrateKbps: Int = 4000,
    var keyFrameIntervalSec: Int = 2,
    var useFullUrl: Boolean = true,
    var fullUrl: String = "",
    var serverUrl: String = "",
    var streamKey: String = "",
    var zoom: Float = 1f,
    var micMuted: Boolean = false,
    var allowCleartextWeb: Boolean = false,
    var webCaptureGpu: Boolean = true,
    var cameraRotationFix: Int = 0,
    /** 麦克风本地降噪，默认开启。 */
    var noiseSuppression: Boolean = true,
    /** 直播时省电：预览降帧、无操作自动调暗屏幕。推流画面不受影响。 */
    var powerSave: Boolean = true,
    /** 直播中无操作自动调暗屏幕（默认关：调暗后看起来像息屏，容易误会）。 */
    var autoDim: Boolean = false,
    /** 网页 / 视频的声音是否也在手机上播放（默认否：只进推流）。 */
    var monitorLocal: Boolean = false,
    /** 第二路推流（纯摄像头画面）：地址（加密保存）、是否带麦克风声音、分辨率、码率。 */
    var camUrl: String = "",
    var camAudio: Boolean = true,
    var camResolution: Resolution = Resolution.P720,
    var camBitrateKbps: Int = 2500,
    /** 助播截图通道：服务域名、直播间 liveId、设备接入密钥（加密保存）、是否在打开 App 时自动连接。 */
    var visionBaseUrl: String = "https://pre-live.hemaos.com",
    var visionLiveId: String = "",
    var visionApiKey: String = "",
    var visionEnabled: Boolean = false,
    /** 本机固定设备 ID（首次运行生成，之后不变）。 */
    var deviceId: String = "",
    /** 网页采集帧率，0 = 跟随输出帧率。 */
    var webCaptureFps: Int = 0,
    /** 云端配置：接口地址、直播间 liveId、访问密钥（加密保存）。只属于本机，不随配置导出。 */
    var cloudConfigUrl: String = "",
    var cloudLiveId: String = "",
    /** 云端下发的截图凭证失效时间（Unix 毫秒，0 = 未知）。只属于本机。 */
    var visionKeyExpiresAt: Long = 0,
    var cloudConfigToken: String = "",
    val layers: MutableList<Layer> = mutableListOf(),
) {
    val canvasWidth get() = if (orientation == CanvasOrientation.LANDSCAPE) resolution.long else resolution.short
    val canvasHeight get() = if (orientation == CanvasOrientation.LANDSCAPE) resolution.short else resolution.long

    companion object {
        private const val PREFS = "config"
        private const val KEY_ALIAS = "livecomposer_stream_key"

        fun load(ctx: Context): AppConfig {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val c = AppConfig()
            c.deviceId = deviceId(p)
            val json = p.getString("json", null) ?: return c.withDefaults(firstRun = true)
            try { c.applySettingsJson(JSONObject(json)) } catch (_: Exception) {}
            // 完整 URL 中也包含密钥，同样加密保存
            c.fullUrl = decrypt(p.getString("fullUrlEnc", null)) ?: ""
            c.camUrl = decrypt(p.getString("camUrlEnc", null)) ?: ""
            c.visionApiKey = decrypt(p.getString("visionApiKeyEnc", null)) ?: ""
            c.streamKey = decrypt(p.getString("streamKeyEnc", null)) ?: ""
            c.cloudConfigToken = decrypt(p.getString("cloudConfigTokenEnc", null)) ?: ""
            return c.withDefaults(firstRun = false)
        }

        /** 本机固定设备 ID：首次生成后单独保存，不随配置重置而变化（卸载重装才会变）。 */
        private fun deviceId(p: android.content.SharedPreferences): String {
            p.getString("deviceId", null)?.takeIf { it.isNotBlank() }?.let { return it }
            val id = "lc-" + java.util.UUID.randomUUID().toString()
            p.edit().putString("deviceId", id).apply()
            return id
        }

        private fun decrypt(s: String?): String? {
            if (s.isNullOrEmpty()) return null
            return try {
                val raw = Base64.decode(s, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
                String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }

        private fun encrypt(s: String): String {
            if (s.isEmpty()) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(s.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(out, Base64.NO_WRAP)
        }

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            return gen.generateKey()
        }
    }

    private fun withDefaults(firstRun: Boolean): AppConfig {
        // 摄像头是唯一的、不可删除的图层（可隐藏）；老版本配置里没有它时补到最底层并铺满
        if (layers.none { it.type == LayerType.CAMERA }) {
            layers.add(0, Layer(type = LayerType.CAMERA, name = "后置摄像头", x = 0f, y = 0f,
                w = canvasWidth.toFloat(), h = canvasHeight.toFloat()))
        }
        // 首次运行放一个测试网页图层；之后用户删掉就不再自动加回
        if (firstRun && layers.none { it.type == LayerType.WEB }) {
            layers.add(Layer(type = LayerType.WEB, name = "网页图层", x = 0f, y = 0f,
                w = canvasWidth.toFloat(), h = canvasHeight.toFloat(),
                url = "file:///android_asset/testpage/index.html",
                viewportW = canvasWidth, viewportH = canvasHeight))
        }
        return this
    }

    /** 普通设置（不含密钥和本机设备 ID）+ 当前图层，用于保存与导出。 */
    fun settingsJson(): JSONObject = JSONObject().apply {
        put("orientation", orientation.name); put("resolution", resolution.name); put("fps", fps)
        put("videoBitrateKbps", videoBitrateKbps); put("keyFrameIntervalSec", keyFrameIntervalSec)
        put("useFullUrl", useFullUrl); put("serverUrl", serverUrl); put("zoom", zoom.toDouble())
        put("micMuted", micMuted); put("allowCleartextWeb", allowCleartextWeb); put("webCaptureGpu", webCaptureGpu)
        put("cameraRotationFix", cameraRotationFix); put("noiseSuppression", noiseSuppression)
        put("powerSave", powerSave); put("autoDim", autoDim); put("monitorLocal", monitorLocal)
        put("camAudio", camAudio); put("camResolution", camResolution.name); put("camBitrateKbps", camBitrateKbps)
        put("visionBaseUrl", visionBaseUrl); put("visionLiveId", visionLiveId); put("visionEnabled", visionEnabled)
        put("webCaptureFps", webCaptureFps)
        put("cloudConfigUrl", cloudConfigUrl); put("cloudLiveId", cloudLiveId); put("visionKeyExpiresAt", visionKeyExpiresAt)
        put("layers", JSONArray().apply { layers.forEach { put(it.toJson()) } })
    }

    /** 应用一份设置 JSON：缺少的键保留当前值；有 layers 时整体替换图层。不改本机设备 ID。 */
    fun applySettingsJson(o: JSONObject) {
        orientation = runCatching { CanvasOrientation.valueOf(o.getString("orientation")) }.getOrDefault(orientation)
        resolution = runCatching { Resolution.valueOf(o.getString("resolution")) }.getOrDefault(resolution)
        fps = o.optInt("fps", fps).coerceIn(15, 60)
        videoBitrateKbps = o.optInt("videoBitrateKbps", videoBitrateKbps).coerceIn(300, 20_000)
        keyFrameIntervalSec = o.optInt("keyFrameIntervalSec", keyFrameIntervalSec).coerceIn(1, 10)
        useFullUrl = o.optBoolean("useFullUrl", useFullUrl)
        serverUrl = o.optString("serverUrl", serverUrl)
        zoom = o.optDouble("zoom", zoom.toDouble()).toFloat()
        micMuted = o.optBoolean("micMuted", micMuted)
        allowCleartextWeb = o.optBoolean("allowCleartextWeb", allowCleartextWeb)
        webCaptureGpu = o.optBoolean("webCaptureGpu", webCaptureGpu)
        cameraRotationFix = o.optInt("cameraRotationFix", cameraRotationFix).let { if (it in listOf(0, 90, 180, 270)) it else 0 }
        noiseSuppression = o.optBoolean("noiseSuppression", noiseSuppression)
        powerSave = o.optBoolean("powerSave", powerSave)
        autoDim = o.optBoolean("autoDim", autoDim)
        monitorLocal = o.optBoolean("monitorLocal", monitorLocal)
        camAudio = o.optBoolean("camAudio", camAudio)
        camResolution = runCatching { Resolution.valueOf(o.getString("camResolution")) }.getOrDefault(camResolution)
        camBitrateKbps = o.optInt("camBitrateKbps", camBitrateKbps).coerceIn(300, 20_000)
        visionBaseUrl = o.optString("visionBaseUrl", visionBaseUrl).ifBlank { "https://pre-live.hemaos.com" }
        visionLiveId = o.optString("visionLiveId", visionLiveId)
        visionEnabled = o.optBoolean("visionEnabled", visionEnabled)
        webCaptureFps = o.optInt("webCaptureFps", webCaptureFps)
        if (o.has("cloudConfigUrl")) cloudConfigUrl = o.optString("cloudConfigUrl")
        if (o.has("cloudLiveId")) cloudLiveId = o.optString("cloudLiveId")
        if (o.has("visionKeyExpiresAt")) visionKeyExpiresAt = o.optLong("visionKeyExpiresAt")
        o.optJSONArray("layers")?.let { arr ->
            layers.clear()
            for (i in 0 until arr.length()) runCatching { layers.add(Layer.fromJson(arr.getJSONObject(i))) }
        }
    }

    /** 加载后的补全（例如没有摄像头图层时补上）。 */
    fun ensureDefaults() { withDefaults(firstRun = false) }

    fun save(ctx: Context) {
        val o = settingsJson()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("json", o.toString())
            .putString("fullUrlEnc", try { encrypt(fullUrl) } catch (_: Exception) { "" })
            .putString("camUrlEnc", try { encrypt(camUrl) } catch (_: Exception) { "" })
            .putString("visionApiKeyEnc", try { encrypt(visionApiKey) } catch (_: Exception) { "" })
            .putString("streamKeyEnc", try { encrypt(streamKey) } catch (_: Exception) { "" })
            .putString("cloudConfigTokenEnc", try { encrypt(cloudConfigToken) } catch (_: Exception) { "" })
            .apply()
    }
}
