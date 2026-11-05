package app.livecomposer.source

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.util.Base64
import app.livecomposer.mix.AudioMixer
import java.nio.ByteBuffer
import android.widget.FrameLayout

/**
 * 网页图层来源：WebView 放在一个“离屏宿主”里——它挂在窗口上（保证 JS 定时器、requestAnimationFrame、
 * CSS 动画正常运行），但从不画到屏幕上；每当网页有新内容，宿主把它画进合成器提供的 Surface（RGBA，带透明通道），
 * 合成器把这张纹理按图层位置/尺寸叠到摄像头画面上，进入编码器。
 *
 * 两种采集模式：
 *  - GPU：Surface.lockHardwareCanvas()，WebView 走硬件绘制（默认，性能好，需真机验证）；
 *  - CPU：WebView 设为软件层，Surface.lockCanvas() 软件绘制（兼容兜底，性能较低）。
 *
 * 防止中间画面进入直播：开始加载新页面/刷新时冻结采集（合成器继续使用上一帧），
 * 页面加载完成且首帧可绘制（postVisualStateCallback）后才恢复；主文档加载失败则在输出中隐藏该图层。
 */
class WebOverlaySource(
    private val context: Context,
    private val parent: ViewGroup,
    val viewportWidth: Int,
    val viewportHeight: Int,
    private val gpuCapture: Boolean,
    private val allowCleartext: Boolean,
    /** 采集帧率上限（= 输出帧率），避免按屏幕刷新率（60/120Hz）重复绘制。 */
    captureFps: Int,
    private val listener: Listener,
    /** 网页声音混入这里（null = 不采集网页声音）。 */
    private val mixer: AudioMixer? = null,
    /** 在混音器里的源 id（= 图层 id）。 */
    private val audioId: String = "",
    audioVolume: Float = 1f,
) {
    enum class LoadState(val label: String) { IDLE("未加载"), LOADING("加载中"), READY("已加载"), ERROR("加载失败"), CRASHED("网页进程崩溃") }

    interface Listener {
        fun onLoadStateChanged(state: LoadState, message: String?)
        /** 输出中是否应显示该图层（失败时隐藏，摄像头继续推流）。 */
        fun onOutputVisibilityChanged(visible: Boolean)
        /** 实测的网页 CSS 视口尺寸，用于核对“渲染视口”设置。 */
        fun onViewportMeasured(cssWidth: Int, cssHeight: Int, dpr: Float)
    }

    private val main = Handler(Looper.getMainLooper())
    init { mixer?.addSource(audioId, audioVolume) }
    private var host: Host? = null
    private val bridgeScript: String? = if (mixer == null) null else try {
        context.assets.open("webaudio/bridge.js").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.w(TAG, "bridge.js", e); null
    }
    private var documentStartInjected = false
    private var webView: WebView? = null
    @Volatile private var surface: Surface? = null
    private var currentUrl: String? = null
    private var loadSeq = 0L
    private var mainFrameError: String? = null
    /** setInitialScale 换算系数：CSS 像素 = 视图像素 / (scalePercent/100 × k)。首次加载后实测校准。 */
    private var scaleK = context.resources.displayMetrics.density
    private var calibrated = false

    private val minIntervalNs = 1_000_000_000L / captureFps * 9 / 10
    private var lastCaptureNs = 0L
    var captureFrames = 0L; private set
    var lastCaptureMs = 0.0; private set

    /** 离屏宿主：拦截绘制，把子 View 画进目标 Surface。 */
    @SuppressLint("ViewConstructor")
    private inner class Host(ctx: Context) : FrameLayout(ctx) {
        @Volatile var capturing = false

        init {
            setWillNotDraw(false)
            clipChildren = false
        }

        override fun onDescendantInvalidated(child: View, target: View) {
            super.onDescendantInvalidated(child, target)
            invalidate() // 硬件加速下子 View 失效不会重录父级，这里强制触发 dispatchDraw
        }

        override fun draw(canvas: Canvas) {
            // 不调用 super：WebView 不在屏幕上显示
            capture()
        }

        override fun dispatchDraw(canvas: Canvas) {
            capture()
        }

        fun capture() {
            val s = surface ?: return
            if (!capturing || !s.isValid) return
            val t0 = System.nanoTime()
            // 持续采集，保证 CSS/Canvas 动画每帧都能进入画面；按输出帧率限速
            postInvalidateOnAnimation()
            if (t0 - lastCaptureNs < minIntervalNs) return
            lastCaptureNs = t0
            val c: Canvas = try {
                if (gpuCapture) s.lockHardwareCanvas() else s.lockCanvas(null)
            } catch (e: Exception) {
                Log.w(TAG, "lockCanvas", e); return
            }
            try {
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                for (i in 0 until childCount) {
                    val child = getChildAt(i)
                    if (child.visibility == View.VISIBLE) drawChild(c, child, drawingTime)
                }
            } finally {
                s.unlockCanvasAndPost(c)
            }
            captureFrames++
            lastCaptureMs = (System.nanoTime() - t0) / 1e6
        }
    }

    fun attach(targetSurface: Surface) {
        surface = targetSurface
        if (host == null) createWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val h = Host(context)
        // 宿主放在预览下方（index 0），尺寸 = 渲染视口（像素），可以超出屏幕
        parent.addView(h, 0, FrameLayout.LayoutParams(viewportWidth, viewportHeight))
        val wv = WebView(context)
        wv.setBackgroundColor(Color.TRANSPARENT) // 透明底，不能是白底
        if (!gpuCapture) wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 网页声音经音频桥混进直播、不外放，允许自动播放（叠加页的提示音、背景音乐）
            mediaPlaybackRequiresUserGesture = mixer == null
            loadWithOverviewMode = false
            useWideViewPort = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            textZoom = 100
            if (Build.VERSION.SDK_INT >= 33) {
                isAlgorithmicDarkeningAllowed = false // 不强制深色模式
            } else {
                @Suppress("DEPRECATION")
                forceDark = WebSettings.FORCE_DARK_OFF
            }
        }
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.setInitialScale(scalePercent())
        wv.webViewClient = Client()
        if (bridgeScript != null) {
            wv.addJavascriptInterface(AudioBridge(), "__lcAudio")
            documentStartInjected = addDocumentStartScript(wv, bridgeScript)
        }
        h.addView(wv, FrameLayout.LayoutParams(viewportWidth, viewportHeight))
        host = h
        webView = wv
    }

    private fun scalePercent(): Int {
        // 目标：网页 CSS 视口 = viewportWidth × viewportHeight（1 CSS px = 1 输出像素，与 OBS 浏览器源一致）
        return maxOf(1, Math.round(100f / scaleK))
    }

    fun load(url: String) {
        val u = url.trim()
        if (u.startsWith("http://", ignoreCase = true) && !allowCleartext) {
            setError("当前设置不允许 HTTP 明文网页，请改用 HTTPS 或在设置中开启“允许局域网 HTTP 网页”")
            return
        }
        if (!(u.startsWith("http://", true) || u.startsWith("https://", true) || u.startsWith("file:///android_asset/"))) {
            setError("网页地址必须以 http:// 或 https:// 开头")
            return
        }
        currentUrl = u
        if (webView == null) createWebView()
        beginLoad()
        webView?.loadUrl(u)
    }

    fun reload() {
        val u = currentUrl ?: return
        if (webView == null) { load(u); return }
        beginLoad()
        webView?.reload()
    }

    private fun beginLoad() {
        loadSeq++
        mainFrameError = null
        host?.capturing = false // 冻结：直播画面继续使用上一帧网页内容
        listener.onLoadStateChanged(LoadState.LOADING, null)
    }

    private fun setError(msg: String) {
        mainFrameError = msg
        host?.capturing = false
        listener.onOutputVisibilityChanged(false)
        listener.onLoadStateChanged(LoadState.ERROR, msg)
    }

    /** 网页交互模式：把预览上的触摸（已换算为视口像素坐标）转发给 WebView。 */
    fun dispatchTouch(ev: MotionEvent, viewportX: Float, viewportY: Float) {
        val wv = webView ?: return
        val e = MotionEvent.obtain(ev)
        e.setLocation(viewportX, viewportY)
        wv.dispatchTouchEvent(e)
        e.recycle()
    }

    /** 网页音频桥：JS 送来 16 位交错 PCM（Base64），转给混音器。运行在 WebView 的 JS 桥线程。 */
    private inner class AudioBridge {
        private var rate = 0
        private var channels = 0

        @JavascriptInterface
        fun pcm(b64: String, sampleRate: Int, ch: Int) {
            val m = mixer ?: return
            if (sampleRate <= 0 || ch !in 1..2) return
            val bytes = try { Base64.decode(b64, Base64.NO_WRAP) } catch (_: Exception) { return }
            if (sampleRate != rate || ch != channels) {
                rate = sampleRate; channels = ch
                m.configure(audioId, sampleRate, ch, AudioMixer.ENCODING_PCM_16BIT)
            }
            m.write(audioId, ByteBuffer.wrap(bytes))
        }
    }

    /** 不支持文档开始注入的旧 WebView：在页面开始 / 结束时各补注入一次（脚本自身防重复）。 */
    private fun injectFallback(view: WebView) {
        val js = bridgeScript ?: return
        if (!documentStartInjected) view.evaluateJavascript(js, null)
    }

    fun release() {
        mixer?.removeSource(audioId)
        host?.capturing = false
        webView?.let { it.stopLoading(); it.destroy() }
        host?.let { parent.removeView(it) }
        webView = null; host = null; surface = null
    }

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            host?.capturing = false
            injectFallback(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            injectFallback(view)
            val seq = loadSeq
            if (mainFrameError != null) return
            // 等页面首帧真正可绘制后再恢复采集，避免把白屏/半成品推出去
            view.postVisualStateCallback(seq, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (requestId != loadSeq || mainFrameError != null) return
                    measureViewport(view)
                }
            })
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) setError("网页加载失败：${error.description}（${error.errorCode}）")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame) setError("网页返回 HTTP ${errorResponse.statusCode}")
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel() // HTTPS 证书错误正常报错，不忽略
            setError("HTTPS 证书错误（${sslReason(error)}）")
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url.toString()
            if (request.isForMainFrame && u.startsWith("http://", true) && !allowCleartext) {
                setError("页面跳转到 HTTP 明文地址被阻止"); return true
            }
            return false
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // 网页进程崩溃：销毁并隐藏图层，摄像头推流不受影响；用户可点“刷新”重建
            main.post {
                host?.capturing = false
                host?.let { parent.removeView(it) }
                webView?.destroy()
                webView = null; host = null
                listener.onOutputVisibilityChanged(false)
                listener.onLoadStateChanged(LoadState.CRASHED, if (detail.didCrash()) "网页进程崩溃，可点击刷新恢复" else "网页进程被系统回收，可点击刷新恢复")
            }
            return true
        }
    }

    private fun measureViewport(view: WebView) {
        view.evaluateJavascript("(function(){return [window.innerWidth, window.innerHeight, window.devicePixelRatio].join(',')})()") { r ->
            val parts = r.trim('"').split(',')
            val w = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
            val hgt = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
            val dpr = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
            if (!calibrated && w > 0 && Math.abs(w - viewportWidth) > 2) {
                // 实测换算系数后重新设置缩放并刷新一次（期间仍保持冻结）
                calibrated = true
                val pct = scalePercent()
                scaleK = viewportWidth * 100f / (pct * w)
                Log.i(TAG, "viewport calibrate: css=$w px=$viewportWidth pct=$pct k=$scaleK")
                view.setInitialScale(scalePercent())
                reload()
                return@evaluateJavascript
            }
            calibrated = true
            listener.onViewportMeasured(w.toInt(), hgt.toInt(), dpr)
            host?.capturing = true
            host?.invalidate()
            listener.onOutputVisibilityChanged(true)
            listener.onLoadStateChanged(LoadState.READY, null)
        }
    }

    private fun sslReason(e: SslError) = when (e.primaryError) {
        SslError.SSL_EXPIRED -> "证书已过期"
        SslError.SSL_IDMISMATCH -> "证书域名不匹配"
        SslError.SSL_UNTRUSTED -> "证书不受信任"
        SslError.SSL_NOTYETVALID -> "证书尚未生效"
        SslError.SSL_DATE_INVALID -> "证书日期无效"
        else -> "证书无效"
    }

    companion object {
        private const val TAG = "WebOverlay"
    }
}
