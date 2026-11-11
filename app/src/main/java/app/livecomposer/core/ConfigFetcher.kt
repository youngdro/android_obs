package app.livecomposer.core

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 从云端获取整体配置：`GET <配置接口地址>?liveId=<liveId>&deviceId=<本机设备ID>`，
 * 可选 `Authorization: Bearer <访问密钥>`；响应体为 [ConfigBundle] 格式的 JSON（也接受包在 data / config 字段里的）。
 * 密钥只放请求头，不进 URL、不写日志。
 */
class ConfigFetcher(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
) {
    sealed class Result {
        class Ok(val parsed: ConfigBundle.Parsed) : Result()
        class Fail(val message: String) : Result()
    }

    /** 回调在 OkHttp 的线程上，调用方自行切线程。 */
    fun fetch(url: String, liveId: String, token: String, appVersion: String, deviceId: String, callback: (Result) -> Unit) {
        validateUrl(url)?.let { callback(Result.Fail(it)); return }
        if (liveId.isBlank()) { callback(Result.Fail("请填写 liveId")); return }
        val u = url.trim().toHttpUrlOrNull()!!.newBuilder()
            .setQueryParameter("liveId", liveId.trim())
            .setQueryParameter("deviceId", deviceId)
            .build()
        val req = Request.Builder().url(u).get()
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("X-LiveComposer-Version", appVersion)
            .header("X-LiveComposer-Device", deviceId)
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}") }
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) =
                callback(Result.Fail("无法访问配置地址：${e.javaClass.simpleName}"))

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val code = r.code
                    if (code == 401 || code == 403) { callback(Result.Fail("没有权限（HTTP $code），请检查访问令牌")); return }
                    if (code == 404) { callback(Result.Fail("服务端没有这个直播间的配置（HTTP 404），请检查 liveId 和地址")); return }
                    if (code !in 200..299) { callback(Result.Fail("服务返回 HTTP $code")); return }
                    val len = r.body?.contentLength() ?: -1
                    if (len > ConfigBundle.MAX_BYTES) { callback(Result.Fail("配置太大（超过 2MB）")); return }
                    val text = try {
                        val src = r.body?.source() ?: run { callback(Result.Fail("服务返回空内容")); return }
                        if (src.request(ConfigBundle.MAX_BYTES.toLong() + 1)) { callback(Result.Fail("配置太大（超过 2MB）")); return }
                        src.readUtf8()
                    } catch (e: IOException) { callback(Result.Fail("读取配置失败：${e.javaClass.simpleName}")); return }
                    try { callback(Result.Ok(ConfigBundle.parse(text))) }
                    catch (e: IllegalArgumentException) { callback(Result.Fail(e.message ?: "配置格式不对")) }
                }
            }
        })
    }

    companion object {
        fun validateUrl(url: String): String? {
            val u = url.trim().toHttpUrlOrNull() ?: return "请填写完整的配置地址，例如 https://example.com/api/livecomposer/config"
            if (u.scheme != "https" && u.host !in setOf("localhost", "127.0.0.1", "10.0.2.2")) return "配置里可能含推流密钥，只支持 https 地址"
            return null
        }
    }
}
