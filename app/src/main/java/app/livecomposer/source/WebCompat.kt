package app.livecomposer.source

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 在每个页面（含 iframe 外的主文档）开始解析前注入脚本，早于页面自身脚本执行。
 * 需要较新的系统 WebView；不支持时返回 false，调用方退回到 onPageStarted / onPageFinished 注入。
 */
fun addDocumentStartScript(webView: WebView, script: String): Boolean {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
    return try {
        WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        true
    } catch (_: Exception) {
        false
    }
}
