import app.livecomposer.core.ConfigBundle
import app.livecomposer.core.ConfigFetcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** 整体配置导出 / 加载格式与云端获取的检查。 */
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + (if (detail.isNotEmpty()) "  ($detail)" else ""))
    if (!ok) failed++
}

private fun layer(id: String, type: String, extra: JSONObject.() -> Unit = {}) =
    JSONObject().put("id", id).put("type", type).put("name", id).put("x", 0).put("y", 0).put("w", 100).put("h", 100).apply(extra)

private fun sampleSettings() = JSONObject()
    .put("orientation", "PORTRAIT").put("resolution", "P720").put("fps", 30).put("videoBitrateKbps", 3000)
    .put("visionLiveId", "4387566503550045").put("deviceId", "lc-should-not-leave").put("cloudConfigUrl", "https://x/cfg")
    .put("layers", JSONArray()
        .put(layer("cam", "CAMERA"))
        .put(layer("web", "WEB") { put("url", "https://example.com/live2d") })
        .put(layer("img", "IMAGE") { put("imageUri", "content://media/external/images/1") }))

private fun sampleScenes() = JSONArray().put(JSONObject().put("id", "s1").put("name", "开场").put("savedAt", 1L)
    .put("data", JSONObject().put("orientation", "LANDSCAPE").put("layers", JSONArray().put(layer("cam", "CAMERA")).put(layer("v", "MEDIA") { put("mediaUri", "file:///sdcard/a.mp4") }))))

fun main() {
    val secrets = mapOf("fullUrl" to "rtmp://push.example.com/live/abc?auth=1", "streamKey" to "", "camUrl" to "rtmp://b/live/c", "visionApiKey" to "k-123")
    val text = ConfigBundle.build(sampleSettings(), secrets, sampleScenes(), "s1", "0.9.0", 1000L).toString(2)
    val o = JSONObject(text)
    check("导出带 format / version / 应用版本", o.optString("format") == ConfigBundle.FORMAT && o.optInt("version") == 1 && o.getJSONObject("app").optString("version") == "0.9.0")
    check("导出不含本机设备 ID 和云端地址", !o.getJSONObject("settings").has("deviceId") && !o.getJSONObject("settings").has("cloudConfigUrl"))

    val p = ConfigBundle.parse(text)
    check("回读：设置、图层数", p.settings.optString("orientation") == "PORTRAIT" && p.layerCount == 3)
    check("回读：密钥", p.secrets?.get("fullUrl") == secrets["fullUrl"] && p.secrets?.get("visionApiKey") == "k-123" && p.secrets?.get("streamKey") == "")
    check("回读：场景与当前场景", p.sceneCount == 1 && p.activeSceneId == "s1")
    check("提示本机图片 / 视频需要重新选择", p.warnings.any { it.contains("2 个图层") && it.contains("本地") }, p.warnings.joinToString())

    val noSecret = ConfigBundle.parse(ConfigBundle.build(sampleSettings(), null, JSONArray(), null, "0.9.0", 1L).toString())
    check("不含密钥时保留本机密钥（secrets 为空）", noSecret.secrets == null && noSecret.warnings.any { it.contains("保留") })
    check("无当前场景时 activeSceneId 为空", noSecret.activeSceneId == null)

    val wrapped = JSONObject().put("ok", true).put("data", JSONObject(text)).toString()
    check("接受包在 data 里的云端响应", runCatching { ConfigBundle.parse(wrapped).layerCount == 3 }.getOrDefault(false))

    val bad = JSONObject(text).apply {
        getJSONObject("settings").getJSONArray("layers").put(JSONObject().put("id", "x").put("type", "HOLOGRAM")).put("oops")
        getJSONArray("scenes").put(JSONObject().put("name", "坏场景"))
    }
    val pb = ConfigBundle.parse(bad.toString())
    check("跳过格式不对的图层和场景", pb.layerCount == 3 && pb.sceneCount == 1 && pb.warnings.any { it.contains("2 个图层格式不对") } && pb.warnings.any { it.contains("场景") }, pb.warnings.joinToString())

    // 服务端实际下发的样式：scenes 为空数组、activeSceneId 为空字符串 → 清空本机场景
    val srv = ConfigBundle.parse("""{"format":"livecomposer-config","version":1,"settings":{"visionLiveId":"123456789","layers":[{"id":"cloud-camera","type":"CAMERA","name":"后置摄像头","x":0,"y":0,"w":1080,"h":1920,"layouts":{}}]},
        "secrets":{"fullUrl":"rtmp://push.example.com/live/s?auth_key=X","streamKey":"","camUrl":"","visionApiKey":"lc_evt_v1.X"},"scenes":[],"activeSceneId":""}""")
    check("服务端样式：空 scenes 清空场景、空 activeSceneId 清除选择", srv.scenes != null && srv.sceneCount == 0 && srv.activeSceneId == null)
    check("服务端样式：空字符串密钥表示清空", srv.secrets?.get("camUrl") == "" && srv.secrets?.get("visionApiKey") == "lc_evt_v1.X")

    fun err(t: String) = try { ConfigBundle.parse(t); null } catch (e: IllegalArgumentException) { e.message }
    check("拒绝非 JSON", err("hello")?.contains("JSON") == true)
    check("拒绝其他格式", err("""{"format":"obs-scene","version":1,"settings":{}}""")?.contains("不是 LiveComposer") == true)
    check("拒绝更新版本", err("""{"format":"livecomposer-config","version":9,"settings":{}}""")?.contains("升级") == true)
    check("拒绝缺少 settings", err("""{"format":"livecomposer-config","version":1}""")?.contains("settings") == true)
    check("拒绝超过 2MB", err(" ".repeat(ConfigBundle.MAX_BYTES + 1))?.contains("2MB") == true)
    val local = JSONObject(text).apply { getJSONObject("settings").put("deviceId", "lc-other").put("cloudConfigToken", "t") }
    check("加载时忽略配置里的本机设备 ID", !ConfigBundle.parse(local.toString()).settings.has("deviceId"))

    // 云端获取
    val server = MockWebServer(); server.start()
    val base = server.url("/api/livecomposer/config").toString()
    val f = ConfigFetcher()
    fun fetch(url: String, liveId: String = "4387566503550045", token: String = "tok-secret-1"): ConfigFetcher.Result {
        val q = LinkedBlockingQueue<ConfigFetcher.Result>()
        f.fetch(url, liveId, token, "0.9.0", "lc-dev-1") { q.add(it) }
        return q.poll(10, TimeUnit.SECONDS) ?: ConfigFetcher.Result.Fail("timeout")
    }
    server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(text))
    val r1 = fetch(base)
    val req = server.takeRequest(5, TimeUnit.SECONDS)
    check("云端：GET 带 liveId、deviceId 参数", req?.method == "GET" && req.requestUrl?.queryParameter("liveId") == "4387566503550045" && req.requestUrl?.queryParameter("deviceId") == "lc-dev-1")
    check("云端：Bearer 访问密钥在请求头、不在 URL", req?.getHeader("Authorization") == "Bearer tok-secret-1" && req.requestUrl.toString().contains("tok-secret-1").not())
    check("云端：带 App 版本头", req?.getHeader("X-LiveComposer-Version") == "0.9.0")
    check("云端：解析成功", (r1 as? ConfigFetcher.Result.Ok)?.parsed?.layerCount == 3)

    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false,"code":"CONFIG_NOT_FOUND","error":"x"}"""))
    check("云端：404 CONFIG_NOT_FOUND 提示管理员保存", (fetch(base) as? ConfigFetcher.Result.Fail)?.message?.let { it.contains("CONFIG_NOT_FOUND") && it.contains("管理员") } == true)
    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false,"code":"SESSION_NOT_FOUND"}"""))
    check("云端：404 SESSION_NOT_FOUND 提示关联直播间", (fetch(base) as? ConfigFetcher.Result.Fail)?.message?.contains("关联") == true)
    server.enqueue(MockResponse().setResponseCode(401).setBody("""{"ok":false,"code":"UNAUTHORIZED","error":"配置访问密钥无效"}"""))
    check("云端：401 提示检查配置访问密钥", (fetch(base) as? ConfigFetcher.Result.Fail)?.message?.contains("配置访问密钥") == true)
    server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3").setBody("""{"ok":false,"code":"POLL_TOO_FAST"}"""))
    check("云端：429 按 Retry-After 提示", (fetch(base) as? ConfigFetcher.Result.Fail)?.message?.contains("3 秒") == true)
    server.enqueue(MockResponse().setResponseCode(503).setBody("""{"ok":false,"code":"BINDING_BUSY"}"""))
    check("云端：503 提示暂不可用", (fetch(base) as? ConfigFetcher.Result.Fail)?.message?.contains("暂不可用") == true)
    server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example.com/steal"))
    val r302 = fetch(base)
    check("云端：不跟随重定向（不把密钥带到别的域名）", (r302 as? ConfigFetcher.Result.Fail)?.message?.contains("重定向") == true && server.requestCount == 7, "requests=${server.requestCount}")
    server.enqueue(MockResponse().setHeader("X-LiveComposer-Vision-Expires-At", "1794279810073").setHeader("X-LiveComposer-Config-Revision", "7").setBody(text))
    val rh = fetch(base) as? ConfigFetcher.Result.Ok
    check("云端：读取截图凭证有效期和修订号", rh?.visionExpiresAt == 1794279810073L && rh.revision == "7")
    server.enqueue(MockResponse().setBody("""{"hello":1}"""))
    check("云端：内容不是配置时给出原因", (fetch(base) as? ConfigFetcher.Result.Fail)?.message?.contains("不是 LiveComposer") == true)
    server.enqueue(MockResponse().setBody(" ".repeat(ConfigBundle.MAX_BYTES + 10)))
    check("云端：超过 2MB 拒绝", (fetch(base) as? ConfigFetcher.Result.Fail)?.message?.contains("2MB") == true)
    check("云端：缺 liveId 不发请求", (fetch(base, liveId = " ") as? ConfigFetcher.Result.Fail)?.message?.contains("liveId") == true)
    check("云端：只允许 https（本机调试地址除外）", ConfigFetcher.validateUrl("http://example.com/cfg") != null && ConfigFetcher.validateUrl("https://example.com/cfg") == null)
    server.shutdown()

    println(if (failed == 0) "全部通过" else "失败 $failed 项")
    exitProcess(if (failed == 0) 0 else 1)
}
