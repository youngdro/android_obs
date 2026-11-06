package app.livecomposer.vision

import android.graphics.Bitmap
import app.livecomposer.gl.Compositor
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * 从合成器取摄像头最新帧（完整画面，不含其他图层），在后台线程压成 JPEG：
 * 先按请求质量压，超过字节上限就逐步降质量（最低 0.4），仍超限再把尺寸缩小到 80% 重试。
 */
class CameraFrameGrabber(
    private val compositor: () -> Compositor?,
    /** 摄像头此刻是否在采集（切后台、未授权时为 false）。 */
    private val cameraRunning: () -> Boolean,
) : FrameGrabber {
    private val encoder = Executors.newSingleThreadExecutor { r -> Thread(r, "snapshot-jpeg").apply { isDaemon = true } }
    @Volatile private var busy = false

    override fun grab(maxLongEdge: Int, quality: Float, maxBytes: Int, callback: (FrameGrabber.Result) -> Unit) {
        val c = compositor()
        if (c == null || !cameraRunning()) { callback(FrameGrabber.Result.Fail("CAMERA_UNAVAILABLE", "摄像头当前未打开")); return }
        if (busy) { callback(FrameGrabber.Result.Fail("BUSY", "上一张截图还在处理")); return }
        busy = true
        c.snapshotCamera(maxLongEdge) { bmp, ageMs ->
            if (bmp == null) {
                busy = false
                callback(FrameGrabber.Result.Fail("NO_FRAME", if (ageMs == -2L) "读取摄像头画面失败" else "采集管线暂时没有可用帧"))
                return@snapshotCamera
            }
            if (ageMs > MAX_FRAME_AGE_MS) {
                bmp.recycle(); busy = false
                callback(FrameGrabber.Result.Fail("NO_FRAME", "摄像头画面已停止更新（${ageMs}ms）"))
                return@snapshotCamera
            }
            val capturedAt = System.currentTimeMillis() - ageMs
            encoder.execute {
                try {
                    val jpeg = encode(bmp, quality, maxBytes)
                    if (jpeg == null) callback(FrameGrabber.Result.Fail("ENCODE_FAILED", "图片压缩后仍超过大小上限"))
                    else callback(FrameGrabber.Result.Ok(jpeg, capturedAt, ageMs))
                } catch (e: Exception) {
                    callback(FrameGrabber.Result.Fail("ENCODE_FAILED", "图片压缩失败：${e.message}"))
                } finally {
                    bmp.recycle(); busy = false
                }
            }
        }
    }

    private fun encode(src: Bitmap, quality: Float, maxBytes: Int): ByteArray? {
        var bmp = src
        val out = ByteArrayOutputStream(64 * 1024)
        repeat(4) { round ->
            var q = (quality * 100).toInt().coerceIn(40, 95)
            while (true) {
                out.reset()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= maxBytes) {
                    if (bmp !== src) bmp.recycle()
                    return out.toByteArray()
                }
                if (q <= 40) break
                q = maxOf(40, q - 10)
            }
            val nb = Bitmap.createScaledBitmap(bmp, maxOf(2, (bmp.width * 0.8f).toInt()), maxOf(2, (bmp.height * 0.8f).toInt()), true)
            if (bmp !== src) bmp.recycle()
            bmp = nb
        }
        if (bmp !== src) bmp.recycle()
        return null
    }

    fun shutdown() = encoder.shutdown()

    companion object {
        /** 协议要求帧龄 0～2000ms；留 500ms 余量给压缩和传输。 */
        private const val MAX_FRAME_AGE_MS = 1500L
    }
}
