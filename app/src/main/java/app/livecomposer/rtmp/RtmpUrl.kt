package app.livecomposer.rtmp

/**
 * 推流地址解析。纯 Kotlin，不依赖 Android，方便在 JVM 上测试。
 *
 * 完整 URL：rtmp(s)://host[:port]/app/streamKey[?query]
 *   约定 app 为路径第一段，其余部分（含 query）为 stream name，与 OBS / ffmpeg 的常见行为一致。
 * 分开填写：server = rtmp(s)://host[:port]/app[/...]，key = streamKey，此时 app 取 server 的完整路径。
 */
data class RtmpUrl(
    val secure: Boolean,
    val host: String,
    val port: Int,
    val app: String,
    val streamName: String,
) {
    val scheme: String get() = if (secure) "rtmps" else "rtmp"
    private val defaultPort: Int get() = if (secure) 443 else 1935
    val tcUrl: String
        get() = "$scheme://$host${if (port != defaultPort) ":$port" else ""}/$app"

    /** 日志/界面显示用：Stream Key 脱敏。 */
    fun masked(): String = "$tcUrl/${maskKey(streamName)}"

    override fun toString(): String = masked()

    companion object {
        fun maskKey(key: String): String = when {
            key.isEmpty() -> ""
            key.length <= 4 -> "****"
            else -> key.take(2) + "****" + key.takeLast(2)
        }

        @Throws(RtmpUrlException::class)
        fun parse(full: String): RtmpUrl {
            val (secure, host, port, path) = splitBase(full.trim())
            val slash = path.indexOf('/')
            if (slash <= 0 || slash == path.length - 1) {
                throw RtmpUrlException("推流地址缺少应用名或 Stream Key，格式应为 rtmp://服务器/应用名/密钥")
            }
            return RtmpUrl(secure, host, port, path.substring(0, slash), path.substring(slash + 1))
        }

        @Throws(RtmpUrlException::class)
        fun fromServerAndKey(server: String, key: String): RtmpUrl {
            val k = key.trim().trimStart('/')
            if (k.isEmpty()) throw RtmpUrlException("Stream Key 不能为空")
            val (secure, host, port, path) = splitBase(server.trim().trimEnd('/'))
            if (path.isEmpty()) throw RtmpUrlException("服务器地址缺少应用名，例如 rtmp://example.com/live")
            return RtmpUrl(secure, host, port, path, k)
        }

        private data class Base(val secure: Boolean, val host: String, val port: Int, val path: String)

        private fun splitBase(url: String): Base {
            val idx = url.indexOf("://")
            if (idx < 0) throw RtmpUrlException("推流地址必须以 rtmp:// 或 rtmps:// 开头")
            val secure = when (url.substring(0, idx).lowercase()) {
                "rtmp" -> false
                "rtmps" -> true
                else -> throw RtmpUrlException("仅支持 rtmp:// 与 rtmps:// 地址")
            }
            val rest = url.substring(idx + 3)
            val slash = rest.indexOf('/').let { if (it < 0) rest.length else it }
            val hostPort = rest.substring(0, slash)
            val path = if (slash < rest.length) rest.substring(slash + 1) else ""
            if (hostPort.isEmpty()) throw RtmpUrlException("推流地址缺少服务器主机名")
            val host: String
            val port: Int
            if (hostPort.startsWith("[")) { // IPv6
                val end = hostPort.indexOf(']')
                if (end < 0) throw RtmpUrlException("IPv6 地址格式错误")
                host = hostPort.substring(1, end)
                port = parsePort(hostPort.substring(end + 1).removePrefix(":"), secure)
            } else {
                val colon = hostPort.lastIndexOf(':')
                if (colon >= 0) {
                    host = hostPort.substring(0, colon)
                    port = parsePort(hostPort.substring(colon + 1), secure)
                } else {
                    host = hostPort
                    port = if (secure) 443 else 1935
                }
            }
            if (host.isEmpty()) throw RtmpUrlException("推流地址缺少服务器主机名")
            return Base(secure, host, port, path)
        }

        private fun parsePort(s: String, secure: Boolean): Int {
            if (s.isEmpty()) return if (secure) 443 else 1935
            val p = s.toIntOrNull() ?: throw RtmpUrlException("端口号无效：$s")
            if (p !in 1..65535) throw RtmpUrlException("端口号无效：$s")
            return p
        }
    }
}

class RtmpUrlException(message: String) : Exception(message)
