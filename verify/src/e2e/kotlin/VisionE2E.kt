import app.livecomposer.vision.DeviceVisionClient
import app.livecomposer.vision.FrameGrabber
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.system.exitProcess

/**
 * 助播截图通道端到端检查：按“设备 HTTP 事件信箱 v1”写一个模拟服务端（GET 轮询 + POST 回执），
 * 用 App 里的 DeviceVisionClient 对接，逐项校验请求格式与行为。截图用合成画面代替摄像头。
 */
private const val API_KEY = "test-device-key-0123456789"
private const val LIVE_ID = "181085096314410"
private const val SESSION = "sess-1"
private const val DEVICE = "lc-0b5b3a7e-1d2c-4f7a-9e44-2f6f3b7c9d10"

private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + (if (detail.isNotEmpty()) "  ($detail)" else ""))
    if (!ok) failed++
}

/** 合成一张 1280×720 的画面，按请求缩放、压 JPEG。mode 控制失败 / 延迟场景。 */
class FakeGrabber : FrameGrabber {
    @Volatile var mode = "ok"
    val grabs = AtomicInteger()
    override fun grab(maxLongEdge: Int, quality: Float, maxBytes: Int, callback: (FrameGrabber.Result) -> Unit) {
        grabs.incrementAndGet()
        Thread {
            when (mode) {
                "fail" -> callback(FrameGrabber.Result.Fail("NO_FRAME", "采集管线暂时没有可用帧"))
                "late" -> { Thread.sleep(1500); callback(ok(maxLongEdge, quality)) }
                "slow" -> { Thread.sleep(700); callback(ok(maxLongEdge, quality)) }
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

/** 模拟信箱：GET 下发排队的事件（每次领取生成新 receipt 和租约），POST /ack 记录回执。 */
class Mailbox : Dispatcher() {
    class Ev(val id: String, val payload: JSONObject, val leaseMs: Long, var deliveries: Int = 0, var leaseUntil: Long = 0, var receipt: String = "")
    val events = CopyOnWriteArrayList<Ev>()
    val polls = CopyOnWriteArrayList<Long>()
    val pollReqs = CopyOnWriteArrayList<RecordedRequest>()
    val acks = LinkedBlockingQueue<Pair<RecordedRequest, JSONObject>>()
    val inFlight = AtomicInteger(); @Volatile var overlap = false
    @Volatile var pollStatus = 200
    @Volatile var pollAfterMs = 300L
    /** 回执响应：返回 (http, code)；null 表示正常 200。 */
    @Volatile var ackFail: ((Int) -> Pair<Int, String>?)? = null
    val ackCount = AtomicInteger()
    @Volatile var redeliverNow = false

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath ?: ""
        if (request.getHeader("Authorization") != "Bearer $API_KEY") return err(401, "UNAUTHORIZED")
        if (path == "/api/tb-live-live2d/device-events" && request.method == "GET") {
            if (inFlight.incrementAndGet() > 1) overlap = true
            try {
                Thread.sleep(30)
                polls.add(System.currentTimeMillis()); pollReqs.add(request)
                if (pollStatus != 200) return err(pollStatus, when (pollStatus) { 404 -> "SESSION_NOT_FOUND"; 503 -> "NOT_CONFIGURED"; else -> "X" })
                val now = System.currentTimeMillis()
                val out = org.json.JSONArray()
                for (e in events) {
                    if (e.leaseUntil > now && !redeliverNow) continue
                    e.deliveries++; e.receipt = "r-${e.id}-${e.deliveries}"; e.leaseUntil = now + e.leaseMs
                    out.put(JSONObject().put("eventId", e.id).put("version", 1).put("createdAt", now).put("expiresAt", now + 15000)
                        .put("receipt", e.receipt).put("leaseExpiresAt", e.leaseUntil).put("payload", e.payload))
                }
                redeliverNow = false
                return MockResponse().setBody(JSONObject().put("ok", true).put("transport", "http-poll").put("pollAfterMs", pollAfterMs)
                    .put("serverTime", now).put("liveId", LIVE_ID).put("sessionId", SESSION).put("deviceId", DEVICE).put("events", out).toString())
            } finally { inFlight.decrementAndGet() }
        }
        if (path == "/api/tb-live-live2d/device-events/ack" && request.method == "POST") {
            val n = ackCount.incrementAndGet()
            val o = JSONObject(request.body.readUtf8())
            acks.add(request to o)
            ackFail?.invoke(n)?.let { (h, c) -> return err(h, c) }
            val e = events.firstOrNull { it.id == o.optString("eventId") } ?: return err(404, "EVENT_NOT_FOUND")
            if (e.receipt != o.optString("receipt")) return err(409, "LEASE_INVALID")
            events.remove(e)
            return MockResponse().setBody(JSONObject().put("ok", true).put("eventId", e.id).put("duplicate", false).toString())
        }
        return err(404, "NOT_FOUND")
    }

    private fun err(h: Int, code: String) = MockResponse().setResponseCode(h).setBody(JSONObject().put("ok", false).put("code", code).put("error", code).toString())
}

private fun Mailbox.nextAck(timeoutMs: Long = 4000) = acks.poll(timeoutMs, TimeUnit.MILLISECONDS)

private fun captureEvent(id: String, leaseMs: Long = 8000) = Mailbox.Ev(id, JSONObject().put("event", "vision.capture.requested").put("eventVersion", 1)
    .put("data", JSONObject().put("liveId", LIVE_ID).put("deadlineAt", System.currentTimeMillis() + 15000)
        .put("maxLongEdge", 768).put("maxBytes", 262144).put("format", "image/jpeg").put("quality", 0.7)), leaseMs)

fun main() {
    val server = MockWebServer()
    val mb = Mailbox()
    server.dispatcher = mb
    server.start()
    val base = server.url("/").toString().trimEnd('/')
    val grabber = FakeGrabber()
    val statuses = LinkedBlockingQueue<DeviceVisionClient.Status>()
    val lastRef = java.util.concurrent.atomic.AtomicReference(DeviceVisionClient.Status(DeviceVisionClient.State.IDLE))
    val client = DeviceVisionClient(grabber, object : DeviceVisionClient.Listener {
        override fun onStatus(status: DeviceVisionClient.Status) { lastRef.set(status); statuses.add(status) }
    }, log = { println("  log: $it") }, minPollMs = 200, backoffScale = 0.25)
    fun waitState(s: DeviceVisionClient.State, ms: Long = 4000): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (lastRef.get().state == s) return true; Thread.sleep(20) }
        return lastRef.get().state == s
    }
    val cfg = DeviceVisionClient.Config(base, LIVE_ID, API_KEY, DEVICE)

    // 0. 参数校验
    check("参数校验：空密钥被拒绝", DeviceVisionClient.validate(cfg.copy(apiKey = "")) != null)
    check("参数校验：设备 ID 格式", DeviceVisionClient.validate(cfg.copy(deviceId = "bad id!")) != null && DeviceVisionClient.validate(cfg) == null)

    // 1. 轮询
    client.start(cfg)
    check("轮询成功后状态为已连接", waitState(DeviceVisionClient.State.ONLINE))
    Thread.sleep(1300)
    val r0 = mb.pollReqs.first()
    check("GET 带 Bearer 密钥、liveId、deviceId", r0.getHeader("Authorization") == "Bearer $API_KEY" &&
        r0.requestUrl?.queryParameter("liveId") == LIVE_ID && r0.requestUrl?.queryParameter("deviceId") == DEVICE)
    check("密钥不在 URL 里", mb.pollReqs.none { it.requestUrl.toString().contains(API_KEY) })
    val gaps = mb.polls.zipWithNext { a, b -> b - a }
    check("按服务端 pollAfterMs 间隔轮询", gaps.isNotEmpty() && gaps.all { it >= 280 }, "间隔 ${gaps.joinToString()}ms")

    // 2. 截图成功
    mb.events.add(captureEvent("capture-1"))
    val a1 = mb.nextAck()
    val (req1, o1) = a1 ?: (null to JSONObject())
    check("收到截图回执，带 Bearer 密钥和 JSON 类型", req1?.getHeader("Authorization") == "Bearer $API_KEY" && req1.getHeader("Content-Type")?.startsWith("application/json") == true)
    check("回执字段：liveId / eventId / receipt / status=completed", o1.optString("liveId") == LIVE_ID && o1.optString("eventId") == "capture-1" &&
        o1.optString("receipt") == "r-capture-1-1" && o1.optString("status") == "completed")
    val p1 = o1.optJSONObject("payload"); val d1 = p1?.optJSONObject("data")
    check("payload：vision.capture.completed，eventVersion=1，success=true", p1?.optString("event") == "vision.capture.completed" && p1.opt("eventVersion") == 1 && d1?.opt("success") == true)
    val url = d1?.optString("imageUrl") ?: ""
    check("imageUrl 是 data:image/jpeg;base64,", url.startsWith("data:image/jpeg;base64,"))
    val jpeg = try { Base64.getDecoder().decode(url.substringAfter(",")) } catch (_: Exception) { ByteArray(0) }
    val img = try { ImageIO.read(ByteArrayInputStream(jpeg)) } catch (_: Exception) { null }
    check("图片可解码，长边 ≤ 768，字节 ≤ 256KiB", img != null && maxOf(img.width, img.height) <= 768 && jpeg.size <= 262144, "${img?.width}×${img?.height} ${jpeg.size}B")
    check("整个回执请求 ≤ 384KiB", (req1?.bodySize ?: Long.MAX_VALUE) <= 384 * 1024)
    val cap = d1?.opt("capturedAt"); val age = d1?.opt("frameAgeMs")
    check("capturedAt 为整数毫秒且接近当前时间", cap is Number && (cap is Long || cap is Int) && kotlin.math.abs(cap.toLong() - System.currentTimeMillis()) < 30_000)
    check("frameAgeMs 在 0～2000", (age as? Number)?.toLong()?.let { it in 0L..2000L } == true)
    Thread.sleep(400)
    check("截图次数计入状态", lastRef.get().captures == 1)
    check("回执被接受后不再重复处理", mb.nextAck(800) == null)

    // 3. 截图失败
    grabber.mode = "fail"
    mb.events.add(captureEvent("capture-2"))
    val o2 = mb.nextAck()?.second ?: JSONObject()
    val d2 = o2.optJSONObject("payload")?.optJSONObject("data")
    check("截图失败：status=failed、success=false、errorCode=NO_FRAME、不带图片",
        o2.optString("status") == "failed" && d2?.opt("success") == false && d2.optString("errorCode") == "NO_FRAME" && !d2.has("imageUrl"))

    // 4. 执行中再次领到同一事件：不并行执行，回执用新凭据
    grabber.mode = "slow"
    val g0 = grabber.grabs.get()
    mb.events.add(captureEvent("capture-3"))
    Thread.sleep(350); mb.redeliverNow = true
    val o3 = mb.nextAck()?.second ?: JSONObject()
    check("同一事件只截图一次", grabber.grabs.get() - g0 == 1, "${grabber.grabs.get() - g0} 次")
    check("回执使用最新领取凭据", o3.optString("receipt") == "r-capture-3-2", o3.optString("receipt"))
    check("没有重复回执", mb.nextAck(800) == null)

    // 5. 超过租约的结果不上传
    grabber.mode = "late"
    mb.events.add(captureEvent("capture-4", leaseMs = 800))
    check("超过租约的结果不上传（等待重新投递取新帧）", mb.nextAck(1600) == null)
    grabber.mode = "ok"
    val o4 = mb.nextAck(4000)?.second ?: JSONObject()
    check("重新投递后用新凭据回传新帧", o4.optString("eventId") == "capture-4" && o4.optString("status") == "completed" && !o4.optString("receipt").endsWith("-1"), o4.optString("receipt"))
    Thread.sleep(200)
    check("新凭据回执被服务端接受", mb.events.none { it.id == "capture-4" })
    mb.events.clear()

    // 6. 回执 5xx：用相同请求体重试
    val n0 = mb.ackCount.get()
    mb.ackFail = { n -> if (n == n0 + 1) 502 to "BAD_GATEWAY" else null }
    mb.events.add(captureEvent("capture-5"))
    val first = mb.nextAck()?.second?.toString(); val second = mb.nextAck()?.second?.toString()
    check("回执失败后用相同 eventId、receipt 和内容重试", first != null && first == second)
    mb.ackFail = null

    // 7. 回执 409：停止旧上传，不重试同一凭据
    mb.ackFail = { 409 to "LEASE_INVALID" }
    mb.events.add(captureEvent("capture-6", leaseMs = 3000))
    val f6 = mb.nextAck()
    check("409 LEASE_INVALID 后不重试同一凭据", f6 != null && mb.nextAck(900) == null)
    mb.ackFail = null; mb.events.clear()

    // 8. system.event.result 用 system.event.acknowledged 确认；未知事件不回执
    mb.events.add(Mailbox.Ev("result-x", JSONObject().put("event", "system.event.result").put("eventVersion", 1)
        .put("data", JSONObject().put("inReplyTo", "x").put("status", "completed")), 8000))
    val o8 = mb.nextAck()?.second ?: JSONObject()
    check("system.event.result 回 system.event.acknowledged", o8.optJSONObject("payload")?.optString("event") == "system.event.acknowledged" && o8.optString("status") == "completed")
    mb.events.add(Mailbox.Ev("future-1", JSONObject().put("event", "some.future.event").put("eventVersion", 1).put("data", JSONObject()), 8000))
    check("未约定的事件不乱回执", mb.nextAck(1000) == null)
    mb.events.clear()
    check("GET 从不叠加", !mb.overlap)

    // 9. 503 退避，恢复后回到已连接
    mb.pollStatus = 503
    check("503 时进入重试", waitState(DeviceVisionClient.State.RETRYING), lastRef.get().message ?: "")
    mb.pollStatus = 200
    check("服务恢复后回到已连接", waitState(DeviceVisionClient.State.ONLINE, 6000))

    // 10. 404 低频重试
    mb.pollStatus = 404
    check("会话未关联（404）时进入重试并提示错误码", waitState(DeviceVisionClient.State.RETRYING) && lastRef.get().message?.contains("SESSION_NOT_FOUND") == true, lastRef.get().message ?: "")
    val c404 = mb.polls.size; Thread.sleep(1500)
    check("404 低频重试（1.5 秒内不再请求，正式为 8 秒）", mb.polls.size == c404)
    mb.pollStatus = 200
    check("关联后自动恢复", waitState(DeviceVisionClient.State.ONLINE, 4000))

    // 11. 密钥错误
    client.start(cfg.copy(apiKey = "wrong-key-0000000000"))
    check("接入密钥错误（401）时停止并提示", waitState(DeviceVisionClient.State.STOPPED), lastRef.get().message ?: "")
    val c401 = mb.polls.size; Thread.sleep(1200)
    check("401 后不再轮询", mb.polls.size == c401)

    // 12. 断开后不再轮询
    client.start(cfg)
    waitState(DeviceVisionClient.State.ONLINE)
    client.stop()
    Thread.sleep(300)
    val cs = mb.polls.size; Thread.sleep(900)
    check("断开后停止轮询", mb.polls.size == cs && lastRef.get().state == DeviceVisionClient.State.IDLE)

    client.shutdown()
    server.shutdown()
    println(if (failed == 0) "全部通过" else "失败 $failed 项")
    exitProcess(if (failed == 0) 0 else 1)
}
