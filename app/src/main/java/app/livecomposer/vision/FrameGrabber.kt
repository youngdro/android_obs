package app.livecomposer.vision

/** 从当前采集管线取一张最新摄像头画面并压成 JPEG（不打断推流、不切换摄像头、不临时打开摄像头）。 */
interface FrameGrabber {
    sealed class Result {
        /** jpeg：图片字节；capturedAtMs：摄像头取帧时的 Unix 毫秒；frameAgeMs：抓取时该帧已存在的毫秒数。 */
        class Ok(val jpeg: ByteArray, val capturedAtMs: Long, val frameAgeMs: Long) : Result()
        /** code：CAMERA_UNAVAILABLE / NO_FRAME / PERMISSION_DENIED / BUSY / ENCODE_FAILED 等。 */
        class Fail(val code: String, val message: String) : Result()
    }

    /** 回调可能在任意线程。maxLongEdge：长边上限（像素）；quality：0..1；maxBytes：JPEG 字节上限。 */
    fun grab(maxLongEdge: Int, quality: Float, maxBytes: Int, callback: (Result) -> Unit)
}
