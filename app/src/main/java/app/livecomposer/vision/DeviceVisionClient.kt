package app.livecomposer.vision

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 助播设备截图通道（协议 v1）：
 * 发现接口 → WSS → 5 秒内 auth → 心跳 → 收到 vision.capture.requested 时快速 ACK、抓当前摄像头帧、回传一张 JPEG。
 *
 * - 这是独立的可选能力：连接失败、断线、截图失败都不影响推流。
 * - 断线按 1、2、4、8、15 秒（加随机抖动）退避，每次重连都重新请求发现接口拿新令牌，不复用旧 token、不补发旧结果。
 * - 接入密钥错误、已有其他设备在线等业务错误不自动抢连，停下来等用户处理。
 * - 日志只记请求 ID、耗时、字节数和错误码，不输出接入密钥、连接令牌和图片内容。
 *
 * 所有状态只在内部单线程执行器上修改；对外回调也在该线程，调用方自行切到主线程。
 */
class DeviceVisionClient(
    private val grabber: FrameGrabber,
    private val listener: Listener,
    private val log: (String) -> Unit = {},
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS) // 标准 WebSocket Ping；JSON ping 另行应答
        .build(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Config(val baseUrl: String, val liveId: String, val apiKey: String, val deviceId: String)

    enum class State(val label: String) {
        IDLE("未连接"), DISCOVERING("获取连接信息"), CONNECTING("连接中"), AUTHENTICATING("鉴权中"),
        ONLINE("已连接"), RETRYING("重连中"), STOPPED("已停止"),
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

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "device-vision").apply { isDaemon = true } }
    private var config: Config? = null
    private var ws: WebSocket? = null
    /** 每次连接尝试的代号：旧连接 / 旧请求的回调一律忽略。 */
    private var gen = 0
    private var attempt = 0
    private var retryTask: ScheduledFuture<*>? = null
    private var authTimeout: ScheduledFuture<*>? = null
    private var sessionId: String? = null
    private var deviceId: String = ""
    private var capture = Capture()
    private var status = Status(State.IDLE)

    private data class Capture(val maxLongEdge: Int = 768, val maxBytes: Int = 262_144, val timeoutMs: Long = 4000)

    // ------------------------------------------------------------------ 对外

    fun start(cfg: Config) = exec.execute {
        stopInternal()
        val err = validate(cfg)
        if (err != null) { publish(Status(State.STOPPED, err)); return@execute }
        config = cfg
        deviceId = cfg.deviceId
        attempt = 0
        status = Status(State.IDLE)
        discover()
    }

    fun stop() = exec.execute { stopInternal(); config = null; publish(status.copy(state = State.IDLE, message = null, sessionId = null)) }

    fun shutdown() { exec.execute { stopInternal() }; exec.shutdown() }

    // ------------------------------------------------------------------ 发现

    private fun discover() {
        val cfg = config ?: return
        val g = ++gen
        publish(status.copy(state = State.DISCOVERING, message = null))
        val url = cfg.baseUrl.trimEnd('/').toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegments("api/tb-live-live2d/device-vision/connection")
            ?.addQueryParameter("liveId", cfg.liveId)
            ?.addQueryParameter("deviceId", cfg.deviceId)
            ?.build()
        if (url == null) { publish(Status(State.STOPPED, "服务域名格式不对")); return }
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${cfg.apiKey}").header("Cache-Control", "no-store").get().build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = exec.execute {
                if (g == gen) retry("无法访问服务：${e.message ?: e.javaClass.simpleName}")
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                val body = try { response.body?.string() ?: "" } catch (_: Exception) { "" } finally { response.close() }
                exec.execute { if (g == gen) onDiscovered(code, body, retryAfter) }
            }
        })
    }

    private fun onDiscovered(httpCode: Int, body: String, retryAfterSec: Long?) {
        val o = try { JSONObject(body) } catch (_: Exception) { null }
        val code = o?.optString("code").orEmpty()
        if (httpCode != 200 || o == null || !o.optBoolean("ok")) {
            log("discover http=$httpCode code=$code")
            when (httpCode) {
                401 -> publish(Status(State.STOPPED, "设备接入密钥不正确（401）"))
                400 -> publish(Status(State.STOPPED, "liveId 或设备 ID 格式不对（400）"))
                404 -> retry("没有找到这个直播间的关联会话，请先在助播后台关联直播间", minDelayMs = 15_000)
                429 -> retry("请求过于频繁", minDelayMs = (retryAfterSec ?: 2) * 1000)
                503 -> retry("服务暂不可用（${code.ifEmpty { "503" }}）")
                else -> retry("连接信息获取失败（HTTP $httpCode${if (code.isNotEmpty()) " $code" else ""}）")
            }
            return
        }
        val wsUrl = o.optString("websocketUrl")
        val token = o.optString("connectionToken")
        if (wsUrl.isEmpty() || token.isEmpty()) { retry("连接信息不完整"); return }
        o.optJSONObject("capture")?.let {
            capture = Capture(
                maxLongEdge = it.optInt("maxLongEdge", 768).coerceIn(64, 1024),
                maxBytes = it.optInt("maxBytes", 262_144).coerceIn(16_384, 262_144),
                timeoutMs = it.optLong("timeoutMs", 4000),
            )
        }
        sessionId = o.optString("sessionId").ifEmpty { null }
        connect(wsUrl, token, o.optLong("authTimeoutMs", 5000))
    }

    // ------------------------------------------------------------------ 连接与鉴权

    private fun connect(wsUrl: String, token: String, authTimeoutMs: Long) {
        val g = gen
        publish(status.copy(state = State.CONNECTING, message = null))
        val req = try { Request.Builder().url(wsUrl).build() } catch (e: Exception) { retry("WebSocket 地址无效"); return }
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = exec.execute {
                if (g != gen) { webSocket.close(1000, null); return@execute }
                publish(status.copy(state = State.AUTHENTICATING))
                // 令牌只放在首条消息里，不放 URL
                webSocket.send(JSONObject().put("type", "auth").put("protocolVersion", 1).put("token", token).toString())
                authTimeout = exec.schedule({ if (g == gen && status.state == State.AUTHENTICATING) { webSocket.cancel(); retry("鉴权超时") } },
                    authTimeoutMs.coerceIn(1000, 10_000), TimeUnit.MILLISECONDS)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = exec.execute { if (g == gen) onText(webSocket, text) }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = exec.execute {
                if (g == gen) onDisconnected(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = exec.execute {
                if (g == gen) retry("连接断开：${t.message ?: t.javaClass.simpleName}")
            }
        })
    }

    private fun onDisconnected(code: Int, reason: String) {
        log("closed code=$code")
        when (code) {
            4003 -> retry("直播会话已结束", minDelayMs = 15_000)
            4009 -> retry("会话已换绑或连接失效")
            4001 -> retry("鉴权失败")
            4008 -> retry("服务端限流", minDelayMs = 5_000)
            else -> retry("连接已关闭（$code）")
        }
    }

    private fun onText(webSocket: WebSocket, text: String) {
        val o = try { JSONObject(text) } catch (_: Exception) { return }
        when (o.optString("type")) {
            "auth" -> onAuth(o)
            "ping" -> webSocket.send(JSONObject().put("type", "pong").put("sentAt", now()).toString())
            "pong" -> {}
            "action" -> onAction(webSocket, o)
        }
    }

    private fun onAuth(o: JSONObject) {
        authTimeout?.cancel(false)
        if (o.optBoolean("ok")) {
            sessionId = o.optString("sessionId").ifEmpty { sessionId }
            attempt = 0
            log("auth ok session=$sessionId")
            publish(status.copy(state = State.ONLINE, message = null, sessionId = sessionId))
            return
        }
        val code = o.optString("code")
        log("auth failed code=$code")
        when (code) {
            "ALREADY_CONNECTED" -> stopWith("这个直播会话已有其他截图设备在线，先断开那台设备再连接")
            "SESSION_UNAVAILABLE" -> { status = status.copy(message = "会话不存在或已结束"); retry("会话不存在或已结束", minDelayMs = 15_000) }
            else -> retry("鉴权失败（${code.ifEmpty { "未知" }}）") // AUTH_INVALID / TOKEN_USED / SESSION_REBOUND：重新发现
        }
    }

    // ------------------------------------------------------------------ 截图

    private fun onAction(webSocket: WebSocket, o: JSONObject) {
        val p = o.optJSONObject("payload") ?: return
        val event = p.optString("event")
        val data = p.optJSONObject("data") ?: JSONObject()
        when (event) {
            "vision.capture.requested" -> onCaptureRequested(webSocket, o, p, data)
            "system.action.ack" -> {
                // 服务端对结果的回执：只用于诊断，不再 ACK（避免 ACK 循环）
                val c = data.optString("code")
                log("server ack replyTo=${data.optString("replyTo")} code=$c")
                if (c.isNotEmpty() && c != "CAPTURE_RECEIVED" && c != "CAPTURE_FAILED") {
                    publish(status.copy(lastCapture = "服务端回执：$c"))
                }
            }
        }
    }

    private fun onCaptureRequested(webSocket: WebSocket, o: JSONObject, p: JSONObject, data: JSONObject) {
        val requestId = o.optString("messageId")
        val roomId = p.optString("roomId")
        if (requestId.isEmpty()) return
        if (o.optInt("protocolVersion") != 1 || p.optInt("eventVersion") != 1 || (sessionId != null && roomId != sessionId)) {
            sendAction(webSocket, roomId, "system.action.ack", JSONObject().put("replyTo", requestId).put("status", "rejected")
                .put("code", "INVALID_REQUEST").put("message", "协议版本或会话不匹配"))
            return
        }
        val receivedAt = now()
        val timeoutMs = data.optLong("timeoutMs", capture.timeoutMs).coerceIn(500, 10_000)
        val localDeadline = receivedAt + timeoutMs
        val maxLongEdge = data.optInt("maxLongEdge", capture.maxLongEdge).coerceIn(64, 1024)
        val maxBytes = data.optInt("maxBytes", capture.maxBytes).coerceIn(16_384, 262_144)
        val quality = data.optDouble("quality", 0.7).toFloat().coerceIn(0.3f, 0.95f)
        val format = data.optString("format", "image/jpeg")
        if (format != "image/jpeg") {
            sendAction(webSocket, roomId, "system.action.ack", JSONObject().put("replyTo", requestId).put("status", "rejected")
                .put("code", "UNSUPPORTED_FORMAT").put("message", "只支持 JPEG"))
            return
        }
        // 先快速 ACK，再异步抓帧
        sendAction(webSocket, roomId, "system.action.ack", JSONObject().put("replyTo", requestId).put("status", "accepted"))
        val g = gen
        grabber.grab(maxLongEdge, quality, maxBytes) { r ->
            exec.execute {
                if (g != gen || ws !== webSocket) return@execute // 断线后不补发旧结果
                val took = now() - receivedAt
                if (now() > localDeadline) {
                    log("capture $requestId late ${took}ms, dropped")
                    publish(status.copy(failures = status.failures + 1, lastCapture = "截图超时（${took}ms），已放弃"))
                    return@execute
                }
                when (r) {
                    is FrameGrabber.Result.Ok -> {
                        val b64 = Base64.getEncoder().encodeToString(r.jpeg)
                        sendAction(webSocket, roomId, "vision.capture.completed", JSONObject()
                            .put("replyTo", requestId).put("success", true)
                            .put("imageUrl", "data:image/jpeg;base64,$b64")
                            .put("capturedAt", r.capturedAtMs).put("frameAgeMs", r.frameAgeMs.coerceIn(0, 2000)))
                        log("capture $requestId ok ${r.jpeg.size}B ${took}ms")
                        publish(status.copy(captures = status.captures + 1, lastCapture = "${timeOf(now())} 已回传 ${r.jpeg.size / 1024}KB，用时 ${took}ms"))
                    }
                    is FrameGrabber.Result.Fail -> {
                        sendAction(webSocket, roomId, "vision.capture.completed", JSONObject()
                            .put("replyTo", requestId).put("success", false).put("code", r.code).put("message", r.message))
                        log("capture $requestId failed ${r.code}")
                        publish(status.copy(failures = status.failures + 1, lastCapture = "${timeOf(now())} 截图失败：${r.message}"))
                    }
                }
            }
        }
    }

    private fun sendAction(webSocket: WebSocket, roomId: String, event: String, data: JSONObject) {
        val msg = JSONObject()
            .put("type", "action").put("protocolVersion", 1)
            .put("messageId", UUID.randomUUID().toString())
            .put("sentAt", now())
            .put("target", deviceId)
            .put("payload", JSONObject().put("event", event).put("eventVersion", 1).put("roomId", roomId)
                .put("ackRequired", false).put("data", data))
        webSocket.send(msg.toString())
    }

    // ------------------------------------------------------------------ 重连与停止

    private fun retry(reason: String, minDelayMs: Long = 0) {
        if (config == null) return
        closeSocket()
        val base = BACKOFF_MS[minOf(attempt, BACKOFF_MS.size - 1)]
        attempt++
        val delay = maxOf(base + Random.nextLong(0, base / 4 + 1), minDelayMs)
        publish(status.copy(state = State.RETRYING, message = "$reason，${(delay + 999) / 1000} 秒后重试"))
        retryTask?.cancel(false)
        retryTask = exec.schedule({ if (config != null) discover() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun stopWith(message: String) {
        closeSocket()
        retryTask?.cancel(false)
        publish(status.copy(state = State.STOPPED, message = message))
    }

    private fun stopInternal() {
        gen++
        retryTask?.cancel(false); retryTask = null
        closeSocket()
    }

    private fun closeSocket() {
        authTimeout?.cancel(false)
        val w = ws; ws = null
        gen++
        w?.close(1000, null)
    }

    private fun publish(s: Status) {
        status = s
        listener.onStatus(s)
    }

    companion object {
        private val BACKOFF_MS = longArrayOf(1000, 2000, 4000, 8000, 15_000)
        private val DEVICE_ID = Regex("^[A-Za-z0-9_-]{1,80}$")
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
