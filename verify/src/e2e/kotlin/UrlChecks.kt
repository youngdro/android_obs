import app.livecomposer.rtmp.Amf0
import app.livecomposer.rtmp.RtmpUrl
import app.livecomposer.rtmp.RtmpUrlException

/** 地址解析 / 脱敏 / AMF0 的简单断言。 */
fun main() {
    fun eq(a: Any?, b: Any?) { check(a == b) { "期望 $b，实际 $a" } }
    RtmpUrl.parse("rtmp://example.com/live/abc123").let {
        eq(it.host, "example.com"); eq(it.port, 1935); eq(it.app, "live"); eq(it.streamName, "abc123")
        eq(it.tcUrl, "rtmp://example.com/live"); eq(it.masked(), "rtmp://example.com/live/ab****23")
    }
    RtmpUrl.parse("rtmps://live.example.com:4443/app/key?token=x").let {
        eq(it.secure, true); eq(it.port, 4443); eq(it.streamName, "key?token=x"); eq(it.tcUrl, "rtmps://live.example.com:4443/app")
    }
    eq(RtmpUrl.parse("rtmps://a.b/live/k").port, 443)
    RtmpUrl.fromServerAndKey("rtmp://10.0.0.2:1935/live/sub/", "/stream-key").let {
        eq(it.app, "live/sub"); eq(it.streamName, "stream-key"); eq(it.host, "10.0.0.2")
    }
    for (bad in listOf("http://x/live/k", "rtmp://", "rtmp://host/live", "rtmp://host:99999/a/b", "example.com/live/k")) {
        try { RtmpUrl.parse(bad); error("应拒绝 $bad") } catch (_: RtmpUrlException) {}
    }
    try { RtmpUrl.fromServerAndKey("rtmp://host/live", " "); error("空 key 应拒绝") } catch (_: RtmpUrlException) {}
    val enc = Amf0.encode("connect", 1, linkedMapOf("app" to "live", "n" to 2.5, "b" to true), null)
    val dec = Amf0.decodeAll(enc)
    eq(dec[0], "connect"); eq(dec[1], 1.0); eq((dec[2] as Map<*, *>)["app"], "live"); eq(dec[3], null)
    println("UrlChecks OK")
}
