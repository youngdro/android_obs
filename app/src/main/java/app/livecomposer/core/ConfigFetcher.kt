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
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        // 不跟随重定向：避免把访问密钥带到别的域名
        .followRedirects(false).followSslRedirects(false)
        .build(),
) {
    sealed class Result {
        /** visionExpiresAt：截图凭证失效时间（Unix 毫秒，0 = 未知）；revision：配置修订号（诊断用）。 */
        class Ok(val parsed: ConfigBundle.Parsed, val visionExpiresAt: Long = 0, val revision: String = "") : Result()
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
                    if (code in 300..399) { callback(Result.Fail("服务返回重定向（HTTP $code），为保护密钥不跟随，请检查接口地址")); return }
                    if (code !in 200..299) {
                        val body = try { r.body?.source()?.let { src -> if (src.request(16 * 1024)) "" else src.readUtf8() } ?: "" } catch (_: Exception) { "" }
                        callback(Result.Fail(errorMessage(code, body, r.header("Retry-After")?.toLongOrNull()))); return
                    }
                    val len = r.body?.contentLength() ?: -1
                    if (len > ConfigBundle.MAX_BYTES) { callback(Result.Fail("配置太大（超过 2MB）")); return }
                    val text = try {
                        val src = r.body?.source() ?: run { callback(Result.Fail("服务返回空内容")); return }
                        if (src.request(ConfigBundle.MAX_BYTES.toLong() + 1)) { callback(Result.Fail("配置太大（超过 2MB）")); return }
                        src.readUtf8()
                    } catch (e: IOException) { callback(Result.Fail("读取配置失败：${e.javaClass.simpleName}")); return }
                    val expires = r.header("X-LiveComposer-Vision-Expires-At")?.toLongOrNull() ?: 0L
                    val rev = r.header("X-LiveComposer-Config-Revision").orEmpty().take(40)
                    try { callback(Result.Ok(ConfigBundle.parse(text), expires, rev)) }
                    catch (e: IllegalArgumentException) { callback(Result.Fail(e.message ?: "配置格式不对")) }
                }
            }
        })
    }

    companion object {
        const val DEFAULT_URL = "https://pre-live.hemaos.com/api/tb-live-live2d/device-config"

        /** 服务端错误（{"ok":false,"code":...,"error":...}）转成给用户看的提示。 */
        fun errorMessage(http: Int, body: String, retryAfterSec: Long?): String {
            val o = try { org.json.JSONObject(body) } catch (_: Exception) { null }
            val code = o?.optString("code").orEmpty()
            val err = o?.optString("error").orEmpty().take(80)
            val tail = if (code.isNotEmpty()) "（$code）" else "（HTTP $http）"
            return when {
                http == 401 -> "配置访问密钥不正确或已重置$tail，请向管理员确认"
                http == 403 -> "没有权限拉取这个直播间的配置$tail"
                code == "CONFIG_NOT_FOUND" -> "服务端还没有这个直播间的配置，或配置已过期$tail，请管理员在控制台保存推流地址"
                code == "SESSION_NOT_FOUND" -> "这个 liveId 还没在控制台关联直播间$tail"
                http == 404 -> "服务端没有这个直播间的配置$tail，请检查 liveId 和地址"
                code == "SESSION_REPLACED" -> "直播间关联正在切换$tail，请稍后再试"
                http == 429 -> "拉取太频繁$tail，请 ${maxOf(1L, retryAfterSec ?: 1L)} 秒后再试"
                http == 400 -> "参数不被接受$tail${if (err.isNotEmpty()) "：$err" else ""}，请检查 liveId"
                http >= 500 -> "服务暂不可用$tail，请稍后再试"
                else -> "服务返回 HTTP $http${if (code.isNotEmpty()) " $code" else ""}${if (err.isNotEmpty()) "：$err" else ""}"
            }
        }

        fun validateUrl(url: String): String? {
            val u = url.trim().toHttpUrlOrNull() ?: return "请填写完整的配置地址，例如 https://example.com/api/livecomposer/config"
            if (u.scheme != "https" && u.host !in setOf("localhost", "127.0.0.1", "10.0.2.2")) return "配置里可能含推流密钥，只支持 https 地址"
            return null
        }
    }
}
