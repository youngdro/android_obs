package app.livecomposer.source

import android.webkit.WebView

/** verify/ 编译检查用替身：真实实现依赖 androidx.webkit，由 CI 编译。签名须与 WebCompat.kt 一致。 */
@Suppress("UNUSED_PARAMETER")
fun addDocumentStartScript(webView: WebView, script: String): Boolean = false
