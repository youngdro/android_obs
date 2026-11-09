package app.livecomposer.vision

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 助播设备截图通道：设备 HTTP 事件信箱 v1（轮询）。
 *
 * 约每 2 秒 GET 一次信箱领取事件；收到 vision.capture.requested 时抓当前摄像头帧，
 * 在本次领取租约内 POST 最终回执（completed 带 JPEG，或 failed 带错误码）。
 *
 * - 独立的可选能力：轮询失败、截图失败都不影响推流。
 * - 同一时间只有一个 GET；截图上传独立进行，不暂停轮询。
 * - 同一 eventId 不并行执行；执行中收到新的领取凭据（receipt），回执用新凭据。
 * - 超过租约的结果不上传（等服务端重新投递后取新帧），不回放旧图。
 * - 回执网络失败时用同一请求体重试，但不越过租约。
 * - 接入密钥错误、参数错误停止轮询等用户处理；会话未关联低频重试；503 / 网络错误按 2、4、8、16、30 秒加抖动退避。
 * - 日志只记事件 ID、耗时、字节数和错误码，不输出接入密钥、领取凭据和图片内容。
 *
 * 所有状态只在内部单线程执行器上修改；对外回调也在该线程，调用方自行切到主线程。
 */
class DeviceVisionClient(
    private val grabber: FrameGrabber,
    private val listener: Listener,
    private val log: (String) -> Unit = {},
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val now: () -> Long = System::currentTimeMillis,
    /** 轮询最短间隔（协议要求不小于 1 秒；测试可调小）。 */
    private val minPollMs: Long = 1000,
    /** 退避时长倍率（测试可调小）。 */
    private val backoffScale: Double = 1.0,
) {
    data class Config(val baseUrl: String, val liveId: String, val apiKey: String, val deviceId: String)

    enum class State(val label: String) {
        IDLE("未连接"), CONNECTING("连接中"), ONLINE("已连接"), RETRYING("重试中"), STOPPED("已停止"),
    }

    data class Status(
        val state: State,
        val message: String? = null,
        val sessionId: String? = null,
        val captures: Int = 0,
        val failures: Int = 0,
        val lastCapture: String? = null,
    )

    interface Listener { fun onStatus(status: Status) }

    private class Task(val eventId: String, var receipt: String, var localDeadline: Long)

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "device-vision").apply { isDaemon = true } }
    private var config: Config? = null
    /** 每次 start/stop 递增：旧轮询、旧回执的回调一律忽略。 */
    private var gen = 0
    private var attempt = 0
    private var pollTask: ScheduledFuture<*>? = null
    private var polling = false
    /** 正在执行的事件（按 eventId 去重）。 */
    private val running = HashMap<String, Task>()
    /** 已成功回执的事件，避免响应丢失后重复处理（保留最近 64 个）。 */
    private val done = LinkedHashSet<String>()
    private var status = Status(State.IDLE)

    // ------------------------------------------------------------------ 对外

    fun start(cfg: Config) = exec.execute {
        stopInternal()
        val err = validate(cfg)
        if (err != null) { publish(Status(State.STOPPED, err)); return@execute }
        config = cfg
        attempt = 0
        status = Status(State.CONNECTING)
        publish(status)
        poll()
    }

    fun stop() = exec.execute { stopInternal(); config = null; publish(status.copy(state = State.IDLE, message = null, sessionId = null)) }

    fun shutdown() { exec.execute { stopInternal() }; exec.shutdown() }

    // ------------------------------------------------------------------ 轮询

    private fun base(cfg: Config): HttpUrl.Builder? =
        cfg.baseUrl.trimEnd('/').toHttpUrlOrNull()?.newBuilder()?.addPathSegments("api/tb-live-live2d/device-events")

    private fun poll() {
        val cfg = config ?: return
        if (polling) return
        val url = base(cfg)?.addQueryParameter("liveId", cfg.liveId)?.addQueryParameter("deviceId", cfg.deviceId)?.build()
        if (url == null) { stopWith("服务域名格式不对"); return }
        val g = gen
        polling = true
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${cfg.apiKey}").header("Cache-Control", "no-store").get().build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = exec.execute {
                if (g != gen) return@execute
                polling = false
                backoff("无法访问服务：${e.javaClass.simpleName}")
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                val body = try { response.body?.string() ?: "" } catch (_: Exception) { "" } finally { response.close() }
                exec.execute {
                    if (g != gen) return@execute
                    polling = false
                    onPolled(code, body, retryAfter)
                }
            }
        })
    }

    private fun onPolled(httpCode: Int, body: String, retryAfterSec: Long?) {
        val o = try { JSONObject(body) } catch (_: Exception) { null }
        val code = o?.optString("code").orEmpty()
        if (httpCode == 200 && o?.optBoolean("ok") == true) {
            attempt = 0
            val serverTime = o.optLong("serverTime", 0L)
            val offset = if (serverTime > 0) now() - serverTime else 0L // 本机时间 - 服务端时间
            if (status.state != State.ONLINE) log("poll ok session=${o.optString("sessionId")}")
            publish(status.copy(state = State.ONLINE, message = null, sessionId = o.optString("sessionId").ifEmpty { null }))
            o.optJSONArray("events")?.let { handleEvents(it, offset) }
            schedulePoll(maxOf(minPollMs, o.optLong("pollAfterMs", 2000L)))
            return
        }
        log("poll http=$httpCode code=$code")
        when {
            httpCode == 401 && (code == "TOKEN_EXPIRED" || code == "TOKEN_REVOKED") ->
                stopWith(if (code == "TOKEN_EXPIRED") "截图凭证已过期（TOKEN_EXPIRED），请重新拉取云端配置后再连接" else "截图凭证已被撤销（TOKEN_REVOKED），请重新拉取云端配置后再连接")
            httpCode == 401 -> stopWith("设备接入密钥不正确（401${if (code.isNotEmpty()) " $code" else ""}），请检查或重新拉取云端配置")
            httpCode == 403 -> stopWith("截图凭证与当前 liveId / 设备不匹配（${code.ifEmpty { "403" }}），请重新拉取云端配置")
            httpCode == 409 -> backoff("直播间关联正在切换（${code.ifEmpty { "409" }}）")
            httpCode == 400 || httpCode == 415 -> stopWith("请求参数不被接受（${code.ifEmpty { "$httpCode" }}），请检查 liveId")
            httpCode == 404 -> {
                val why = when {
                    code == "SESSION_NOT_FOUND" -> "服务端没有找到 liveId=${config?.liveId} 的关联会话（SESSION_NOT_FOUND），请在助播控制台关联该直播间或确认会话未结束"
                    code.isEmpty() -> "服务端没有这个接口（HTTP 404，无错误码），可能接口未部署或域名不对"
                    else -> "服务端返回 404 $code"
                }
                val detail = o?.optString("error").orEmpty().take(80)
                publish(status.copy(state = State.RETRYING, message = why + (if (detail.isNotEmpty() && detail != code) "：$detail" else "") + "，8 秒后重试"))
                schedulePoll(scaled(8000))
            }
            httpCode == 429 -> {
                val d = maxOf(scaled(5000), (retryAfterSec ?: 0) * 1000)
                publish(status.copy(state = State.RETRYING, message = "请求过快（${code.ifEmpty { "429" }}），${(d + 999) / 1000} 秒后继续"))
                schedulePoll(d)
            }
            httpCode == 503 -> backoff("服务暂不可用（${code.ifEmpty { "503" }}）")
            else -> backoff("服务返回异常（HTTP $httpCode${if (code.isNotEmpty()) " $code" else ""}）")
        }
    }

    private fun backoff(reason: String) {
        if (config == null) return
        val base = scaled(BACKOFF_MS[minOf(attempt, BACKOFF_MS.size - 1)])
        attempt++
        val delay = base + Random.nextLong(0, base / 4 + 1)
        publish(status.copy(state = State.RETRYING, message = "$reason，${(delay + 999) / 1000} 秒后重试"))
        schedulePoll(delay)
    }

    private fun schedulePoll(delayMs: Long) {
        if (config == null) return
        pollTask?.cancel(false)
        val g = gen
        pollTask = exec.schedule({ if (g == gen) poll() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun scaled(ms: Long) = maxOf(1L, (ms * backoffScale).toLong())

    // ------------------------------------------------------------------ 事件

    private fun handleEvents(arr: JSONArray, offset: Long) {
        for (i in 0 until arr.length()) {
            val ev = arr.optJSONObject(i) ?: continue
            val id = ev.optString("eventId"); val receipt = ev.optString("receipt")
            if (id.isEmpty() || receipt.isEmpty()) continue
            // 本机截止 = 租约到期（换算到本机时间）留 300ms 余量
            val lease = ev.optLong("leaseExpiresAt", 0L).takeIf { it > 0 } ?: ev.optLong("expiresAt", 0L)
            val localDeadline = if (lease > 0) lease + offset - 300 else now() + 7000
            val cur = running[id]
            if (cur != null) {
                // 执行中又领到：后续回执使用新凭据，不并行执行
                cur.receipt = receipt; cur.localDeadline = localDeadline
                continue
            }
            if (id in done) continue
            val payload = ev.optJSONObject("payload")
            when (payload?.optString("event")) {
                "vision.capture.requested" -> capture(Task(id, receipt, localDeadline), payload)
                "system.event.result" -> {
                    val t = Task(id, receipt, localDeadline); running[id] = t
                    sendAck(t, "completed", JSONObject().put("event", "system.event.acknowledged").put("eventVersion", 1).put("data", JSONObject()), null)
                }
                else -> log("event $id ignored: ${payload?.optString("event")}") // 未约定的事件不乱回执
            }
        }
    }

    private fun capture(t: Task, payload: JSONObject) {
        running[t.eventId] = t
        val d = payload.optJSONObject("data") ?: JSONObject()
        if (payload.optInt("eventVersion", -1) != 1) { fail(t, "UNSUPPORTED_EVENT", "事件版本不支持"); return }
        val fmt = d.optString("format", "image/jpeg")
        if (fmt.isNotEmpty() && fmt != "image/jpeg") { fail(t, "UNSUPPORTED_EVENT", "不支持的图片格式"); return }
        val maxEdge = d.optInt("maxLongEdge", 768).coerceIn(64, 1024)
        val maxBytes = d.optInt("maxBytes", MAX_JPEG).coerceIn(16 * 1024, MAX_JPEG)
        val quality = d.optDouble("quality", 0.7).toFloat().coerceIn(0.3f, 0.95f)
        val started = now()
        val g = gen
        grabber.grab(maxEdge, quality, maxBytes) { r ->
            exec.execute {
                if (g != gen || running[t.eventId] !== t) return@execute
                when (r) {
                    is FrameGrabber.Result.Ok -> {
                        if (now() > t.localDeadline) {
                            // 超过租约：不上传旧帧，等服务端重新投递后取新帧
                            log("capture ${t.eventId} late ${now() - started}ms, dropped")
                            running.remove(t.eventId)
                            return@execute
                        }
                        val data = JSONObject().put("success", true)
                            .put("imageUrl", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(r.jpeg))
                            .put("capturedAt", r.capturedAtMs)
                            .put("frameAgeMs", r.frameAgeMs.coerceIn(0, 2000))
                        log("capture ${t.eventId} ok ${r.jpeg.size}B ${now() - started}ms")
                        sendAck(t, "completed", resultPayload(data), r.jpeg.size)
                    }
                    is FrameGrabber.Result.Fail -> fail(t, r.code, r.message)
                }
            }
        }
    }

    private fun resultPayload(data: JSONObject) =
        JSONObject().put("event", "vision.capture.completed").put("eventVersion", 1).put("data", data)

    private fun fail(t: Task, code: String, message: String) {
        val c = if (code in ERROR_CODES) code else "NO_FRAME"
        log("capture ${t.eventId} failed $c")
        publish(status.copy(failures = status.failures + 1, lastCapture = "${timeOf(now())} 失败：$message"))
        sendAck(t, "failed", resultPayload(JSONObject().put("success", false).put("errorCode", c)), null)
    }

    /** 提交最终回执；网络错误 / 5xx 时在租约内用同一 eventId 和请求体重试。 */
    private fun sendAck(t: Task, state: String, payload: JSONObject, imageBytes: Int?) {
        val cfg = config ?: return
        val url = base(cfg)?.addPathSegment("ack")?.build() ?: return
        val receipt = t.receipt
        val body = JSONObject().put("liveId", cfg.liveId).put("deviceId", cfg.deviceId).put("eventId", t.eventId)
            .put("receipt", receipt).put("status", state).put("payload", payload).toString()
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${cfg.apiKey}")
            .post(body.toRequestBody(JSON)).build()
        val g = gen
        val sent = now()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = exec.execute {
                if (g == gen) onAck(t, state, payload, imageBytes, receipt, -1, "", sent)
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val text = try { response.body?.string() ?: "" } catch (_: Exception) { "" } finally { response.close() }
                exec.execute { if (g == gen) onAck(t, state, payload, imageBytes, receipt, code, text, sent) }
            }
        })
    }

    private fun onAck(t: Task, state: String, payload: JSONObject, imageBytes: Int?, receipt: String, httpCode: Int, text: String, sent: Long) {
        val o = try { JSONObject(text) } catch (_: Exception) { null }
        val code = o?.optString("code").orEmpty()
        val isCapture = payload.optString("event") == "vision.capture.completed"
        if (httpCode == 200 && o?.optBoolean("ok") == true) {
            log("ack ${t.eventId} $state http=200 dup=${o.optBoolean("duplicate")} ${now() - sent}ms")
            running.remove(t.eventId)
            done.add(t.eventId); if (done.size > 64) done.remove(done.first())
            if (isCapture && state == "completed") {
                publish(status.copy(captures = status.captures + 1, lastCapture = "${timeOf(now())} 已发送 ${(imageBytes ?: 0) / 1024}KB"))
            }
            return
        }
        log("ack ${t.eventId} http=$httpCode code=$code")
        val retriable = httpCode == -1 || httpCode >= 500 || httpCode == 429
        if (retriable && now() + 500 < t.localDeadline) {
            // 回执重试保持相同 eventId、receipt 和内容；若期间换了新凭据，就用新凭据
            exec.schedule({
                if (running[t.eventId] === t && config != null) sendAck(t, state, payload, imageBytes)
            }, 500, TimeUnit.MILLISECONDS)
            return
        }
        running.remove(t.eventId)
        if (receipt != t.receipt && httpCode == 409) return // 旧凭据失效但已有新凭据：由新一轮处理
        if (isCapture) {
            val why = when {
                httpCode == 409 -> "领取已过期，等待重新投递"
                httpCode == 410 || httpCode == 404 -> "任务已过期或不存在"
                httpCode == 413 -> "图片过大"
                httpCode == 422 -> "图片不合规（$code）"
                httpCode == 401 -> "接入密钥不正确"
                httpCode == -1 -> "网络错误"
                else -> "HTTP $httpCode $code"
            }
            if (state == "completed") publish(status.copy(failures = status.failures + 1, lastCapture = "${timeOf(now())} 发送失败：$why"))
        }
        if (httpCode == 401) stopWith(if (code == "TOKEN_EXPIRED" || code == "TOKEN_REVOKED") "截图凭证已失效（$code），请重新拉取云端配置后再连接" else "设备接入密钥不正确（401），请检查或重新拉取云端配置")
    }

    // ------------------------------------------------------------------ 停止

    private fun stopWith(message: String) {
        gen++
        pollTask?.cancel(false); pollTask = null
        polling = false
        running.clear()
        config = null
        publish(status.copy(state = State.STOPPED, message = message))
    }

    private fun stopInternal() {
        gen++
        pollTask?.cancel(false); pollTask = null
        polling = false
        running.clear()
    }

    private fun publish(s: Status) {
        status = s
        listener.onStatus(s)
    }

    companion object {
        private val BACKOFF_MS = longArrayOf(2000, 4000, 8000, 16_000, 30_000)
        private val DEVICE_ID = Regex("^[A-Za-z0-9_-]{1,80}$")
        private val JSON = "application/json".toMediaType()
        private const val MAX_JPEG = 262_144
        private val ERROR_CODES = setOf("CAMERA_UNAVAILABLE", "NO_FRAME", "PERMISSION_DENIED", "BUSY", "UNSUPPORTED_EVENT")
        const val DEFAULT_BASE_URL = "https://pre-live.hemaos.com"

        fun validate(c: Config): String? = when {
            c.baseUrl.trimEnd('/').toHttpUrlOrNull() == null -> "服务域名格式不对，例如 https://pre-live.hemaos.com"
            c.liveId.isBlank() || !c.liveId.all { it.isDigit() || it.isLetter() || it == '_' || it == '-' } -> "请填写正确的 liveId"
            c.apiKey.isBlank() -> "请填写设备接入密钥"
            !DEVICE_ID.matches(c.deviceId) -> "设备 ID 格式不对"
            else -> null
        }

        private fun timeOf(ms: Long): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }
}
