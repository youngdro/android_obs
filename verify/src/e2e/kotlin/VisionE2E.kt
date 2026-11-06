import app.livecomposer.vision.DeviceVisionClient
import app.livecomposer.vision.FrameGrabber
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.system.exitProcess

/**
 * 助播截图通道端到端检查：按 DEVICE_VISION_WEBSOCKET 协议 v1 写一个模拟服务端（发现接口 + WSS 网关），
 * 用 App 里的 DeviceVisionClient 对接，逐项校验消息格式与行为。截图用合成画面代替摄像头。
 */
private const val API_KEY = "test-device-key-0123456789"
private const val LIVE_ID = "181085096314410"
private const val SESSION = "NlIfQ81UOb"
private const val DEVICE = "lc-0b5b3a7e-1d2c-4f7a-9e44-2f6f3b7c9d10"

private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + (if (detail.isNotEmpty()) "  ($detail)" else ""))
    if (!ok) failed++
}

/** 合成一张 1280×720 的画面，按请求缩放、压 JPEG。mode 控制失败 / 延迟场景。 */
class FakeGrabber : FrameGrabber {
    @Volatile var mode = "ok"
    override fun grab(maxLongEdge: Int, quality: Float, maxBytes: Int, callback: (FrameGrabber.Result) -> Unit) {
        Thread {
            when (mode) {
                "fail" -> callback(FrameGrabber.Result.Fail("NO_FRAME", "采集管线暂时没有可用帧"))
                "late" -> { Thread.sleep(1500); callback(ok(maxLongEdge, quality)) }
                else -> callback(ok(maxLongEdge, quality))
            }
        }.start()
    }

    private fun ok(maxLongEdge: Int, quality: Float): FrameGrabber.Result {
        val s = minOf(1f, maxLongEdge / 1280f)
        val w = (1280 * s).toInt(); val h = (720 * s).toInt()
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(30, 90, 160); g.fillRect(0, 0, w, h)
        g.color = Color.WHITE; g.font = g.font.deriveFont(48f); g.drawString("商品 TEST", 40, h / 2)
        g.dispose()
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val p = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality }
        writer.output = ImageIO.createImageOutputStream(out)
        writer.write(null, IIOImage(img, null, null), p); writer.dispose()
        return FrameGrabber.Result.Ok(out.toByteArray(), System.currentTimeMillis() - 20, 20)
    }
}

/** 模拟网关：记录设备发来的消息，按场景回应。 */
class Gateway(private val server: MockWebServer) : Dispatcher() {
    val discovers = AtomicInteger()
    val tokens = mutableListOf<String>()
    val inbox = LinkedBlockingQueue<JSONObject>()
    @Volatile var socket: WebSocket? = null
    @Volatile var discoverStatus = 200
    @Volatile var authReply: (String) -> JSONObject = { tok ->
        if (tok == tokens.lastOrNull()) JSONObject().put("type", "auth").put("protocolVersion", 1).put("ok", true)
            .put("sessionId", SESSION).put("liveId", LIVE_ID).put("deviceId", DEVICE).put("heartbeatIntervalMs", 10000)
        else JSONObject().put("type", "auth").put("protocolVersion", 1).put("ok", false).put("code", "TOKEN_USED")
    }
    val usedTokens = mutableSetOf<String>()
    @Volatile var lastDiscover: RecordedRequest? = null
    @Volatile var wsPathHadToken = false

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath ?: ""
        if (path == "/api/tb-live-live2d/device-vision/connection") {
            discovers.incrementAndGet(); lastDiscover = request
            if (discoverStatus != 200) return MockResponse().setResponseCode(discoverStatus)
                .setBody(JSONObject().put("ok", false).put("code", if (discoverStatus == 401) "UNAUTHORIZED" else "SESSION_NOT_FOUND").toString())
            if (request.getHeader("Authorization") != "Bearer $API_KEY") return MockResponse().setResponseCode(401).setBody("""{"ok":false,"code":"UNAUTHORIZED"}""")
            val tok = "tok-" + UUID.randomUUID()
            synchronized(tokens) { tokens.add(tok) }
            val ws = server.url("/ws/tb-live-live2d/device-vision").toString().replace("http://", "ws://")
            return MockResponse().setHeader("Cache-Control", "no-store").setBody(JSONObject()
                .put("ok", true).put("protocolVersion", 1).put("liveId", LIVE_ID).put("sessionId", SESSION).put("deviceId", DEVICE)
                .put("websocketUrl", ws).put("connectionToken", tok).put("tokenExpiresAt", System.currentTimeMillis() + 300_000)
                .put("authTimeoutMs", 5000).put("heartbeatIntervalMs", 10000)
                .put("capture", JSONObject().put("maxLongEdge", 768).put("maxBytes", 262144).put("format", "image/jpeg").put("timeoutMs", 4000))
                .toString())
        }
        if (path == "/ws/tb-live-live2d/device-vision") {
            wsPathHadToken = request.requestUrl?.query?.contains("tok-") == true
            return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                var authed = false
                override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val o = JSONObject(text)
                    if (!authed) {
                        val tok = o.optString("token")
                        val reused = !usedTokens.add(tok)
                        val r = if (reused) JSONObject().put("type", "auth").put("ok", false).put("code", "TOKEN_USED") else authReply(tok)
                        webSocket.send(r.toString())
                        if (r.optBoolean("ok")) authed = true else webSocket.close(4001, "auth")
                        inbox.add(o.put("_reused", reused)); return
                    }
                    inbox.add(o)
                }
            })
        }
        return MockResponse().setResponseCode(404)
    }
}

private fun Gateway.next(timeoutMs: Long = 3000): JSONObject? = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS)

private fun captureRequest(id: String, timeoutMs: Long = 3980) = JSONObject()
    .put("type", "action").put("protocolVersion", 1).put("messageId", id).put("sentAt", System.currentTimeMillis()).put("target", DEVICE)
    .put("payload", JSONObject().put("event", "vision.capture.requested").put("eventVersion", 1).put("roomId", SESSION).put("ackRequired", true)
        .put("data", JSONObject().put("liveId", LIVE_ID).put("deadlineAt", System.currentTimeMillis() + timeoutMs).put("timeoutMs", timeoutMs)
            .put("maxLongEdge", 768).put("maxBytes", 262144).put("format", "image/jpeg").put("quality", 0.7)))

fun main() {
    val server = MockWebServer()
    val gw = Gateway(server)
    server.dispatcher = gw
    server.start()
    val base = server.url("/").toString().trimEnd('/')
    val grabber = FakeGrabber()
    val states = LinkedBlockingQueue<DeviceVisionClient.Status>()
    var last: DeviceVisionClient.Status? = null
    val client = DeviceVisionClient(grabber, object : DeviceVisionClient.Listener {
        override fun onStatus(status: DeviceVisionClient.Status) { last = status; states.add(status) }
    }, log = { println("  log: $it") })
    fun waitState(s: DeviceVisionClient.State, ms: Long = 5000): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { val x = states.poll(200, TimeUnit.MILLISECONDS) ?: continue; if (x.state == s) return true }
        return last?.state == s
    }

    // 参数校验
    check("参数校验：空密钥被拒绝", DeviceVisionClient.validate(DeviceVisionClient.Config(base, LIVE_ID, "", DEVICE)) != null)
    check("参数校验：设备 ID 格式", DeviceVisionClient.validate(DeviceVisionClient.Config(base, LIVE_ID, API_KEY, "bad id!")) != null)

    // 1. 发现 + 鉴权
    client.start(DeviceVisionClient.Config(base, LIVE_ID, API_KEY, DEVICE))
    val auth = gw.next()
    val d = gw.lastDiscover
    check("发现接口：Bearer 密钥、liveId、deviceId 正确",
        d?.getHeader("Authorization") == "Bearer $API_KEY" && d.requestUrl?.queryParameter("liveId") == LIVE_ID && d.requestUrl?.queryParameter("deviceId") == DEVICE)
    check("首条消息是 auth，带令牌、protocolVersion=1", auth?.optString("type") == "auth" && auth.optInt("protocolVersion") == 1 && auth.optString("token") == gw.tokens.last())
    check("令牌不在 WebSocket URL 里", !gw.wsPathHadToken)
    check("鉴权成功后状态为已连接", waitState(DeviceVisionClient.State.ONLINE))
    val ws = gw.socket!!

    // 2. JSON 心跳
    ws.send("""{"type":"ping","sentAt":${System.currentTimeMillis()}}""")
    val pong = gw.next()
    check("收到 ping 立即回 pong", pong?.optString("type") == "pong" && pong.has("sentAt"))

    // 3. 截图请求 → ACK → 结果
    val req1 = UUID.randomUUID().toString()
    val t0 = System.currentTimeMillis()
    ws.send(captureRequest(req1).toString())
    val ack = gw.next()
    val ackMs = System.currentTimeMillis() - t0
    val ap = ack?.optJSONObject("payload"); val ad = ap?.optJSONObject("data")
    check("快速 ACK accepted（${ackMs}ms，建议 200ms 内）", ap?.optString("event") == "system.action.ack" && ad?.optString("replyTo") == req1 &&
        ad.optString("status") == "accepted" && ackMs < 500)
    check("ACK 包络：type/protocolVersion/eventVersion/roomId/target/messageId", ack?.optString("type") == "action" && ack.get("protocolVersion") == 1 &&
        ap?.get("eventVersion") == 1 && ap.optString("roomId") == SESSION && ack.optString("target") == DEVICE &&
        ack.optString("messageId").isNotEmpty() && ack.optString("messageId") != req1)
    val res = gw.next()
    val rp = res?.optJSONObject("payload"); val rd = rp?.optJSONObject("data")
    check("结果事件 vision.capture.completed，replyTo 是服务端请求 ID", rp?.optString("event") == "vision.capture.completed" && rd?.optString("replyTo") == req1)
    check("success 是布尔 true", rd?.opt("success") == true)
    val url = rd?.optString("imageUrl") ?: ""
    check("imageUrl 是 data:image/jpeg;base64,", url.startsWith("data:image/jpeg;base64,"))
    val jpeg = try { Base64.getDecoder().decode(url.substringAfter(",")) } catch (_: Exception) { ByteArray(0) }
    val img = try { ImageIO.read(ByteArrayInputStream(jpeg)) } catch (_: Exception) { null }
    check("图片可解码，长边 ≤ 768，字节 ≤ 256KiB", img != null && maxOf(img.width, img.height) <= 768 && jpeg.size <= 262144,
        "${img?.width}×${img?.height} ${jpeg.size}B")
    check("整条消息 ≤ 384KiB", (res?.toString() ?: "").toByteArray().size <= 384 * 1024)
    val cap = rd?.opt("capturedAt"); val age = rd?.opt("frameAgeMs")
    check("capturedAt 为整数毫秒且接近当前时间", cap is Number && (cap is Long || cap is Int) && kotlin.math.abs(cap.toLong() - System.currentTimeMillis()) < 30_000)
    check("frameAgeMs 在 0～2000", (age as? Number)?.toLong()?.let { it in 0L..2000L } == true)

    // 4. 服务端回执：设备不得再 ACK
    ws.send(JSONObject().put("type", "action").put("protocolVersion", 1).put("messageId", "srv-ack-1").put("sentAt", System.currentTimeMillis()).put("target", DEVICE)
        .put("payload", JSONObject().put("event", "system.action.ack").put("eventVersion", 1).put("roomId", SESSION).put("ackRequired", false)
            .put("data", JSONObject().put("replyTo", res?.optString("messageId")).put("status", "accepted").put("code", "CAPTURE_RECEIVED"))).toString())
    check("收到服务端回执后不再回复（避免 ACK 循环）", gw.next(800) == null)

    // 5. 截图失败：ACK accepted 后返回 success=false + code
    grabber.mode = "fail"
    val req2 = UUID.randomUUID().toString()
    ws.send(captureRequest(req2).toString())
    gw.next() // ack
    val fr = gw.next()?.optJSONObject("payload")?.optJSONObject("data")
    check("截图失败：success=false、code=NO_FRAME、不带图片", fr?.optString("replyTo") == req2 && fr.opt("success") == false &&
        fr.optString("code") == "NO_FRAME" && !fr.has("imageUrl"))

    // 6. 超过截止时间：不发送迟到的结果
    grabber.mode = "late"
    val req3 = UUID.randomUUID().toString()
    ws.send(captureRequest(req3, timeoutMs = 800).toString())
    gw.next() // ack
    check("超过截止时间的结果不再发送", gw.next(2500) == null)
    grabber.mode = "ok"

    // 7. 会话换绑（4009）→ 重新发现、用新令牌重新鉴权
    val before = gw.discovers.get()
    ws.close(4009, "rebound")
    val auth2 = gw.next(8000)
    check("断线后重新请求发现接口并用新令牌鉴权", gw.discovers.get() > before && auth2?.optString("type") == "auth" &&
        auth2.optBoolean("_reused") == false && auth2.optString("token") == gw.tokens.last())
    check("重连后恢复已连接", waitState(DeviceVisionClient.State.ONLINE, 8000))
    // 重连后截图仍可用
    val req4 = UUID.randomUUID().toString()
    gw.socket!!.send(captureRequest(req4).toString())
    gw.next()
    check("重连后截图正常", gw.next()?.optJSONObject("payload")?.optJSONObject("data")?.optString("replyTo") == req4)

    // 8. ALREADY_CONNECTED：停止，不抢连
    gw.authReply = { JSONObject().put("type", "auth").put("protocolVersion", 1).put("ok", false).put("code", "ALREADY_CONNECTED") }
    gw.socket!!.close(4009, "dup")
    check("已有其他设备在线时停止并提示", waitState(DeviceVisionClient.State.STOPPED, 8000), last?.message ?: "")
    val n = gw.discovers.get()
    Thread.sleep(3000)
    check("停止后不再自动抢连", gw.discovers.get() == n)

    // 9. 401：停止
    gw.discoverStatus = 401
    client.start(DeviceVisionClient.Config(base, LIVE_ID, API_KEY, DEVICE))
    check("接入密钥错误（401）时停止并提示", waitState(DeviceVisionClient.State.STOPPED), last?.message ?: "")
    val n2 = gw.discovers.get()
    Thread.sleep(2500)
    check("401 后不高频重试", gw.discovers.get() == n2)

    // 10. 404：低频重试
    gw.discoverStatus = 404
    client.start(DeviceVisionClient.Config(base, LIVE_ID, API_KEY, DEVICE))
    check("会话不存在（404）时进入重试并提示", waitState(DeviceVisionClient.State.RETRYING), last?.message ?: "")
    val n3 = gw.discovers.get()
    Thread.sleep(3000)
    check("404 低频重试（3 秒内不再请求）", gw.discovers.get() == n3)

    client.shutdown()
    server.shutdown()
    if (failed > 0) { println("$failed 项失败"); exitProcess(1) }
    println("全部通过")
    exitProcess(0)
}
