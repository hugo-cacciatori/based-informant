package io.github.hugo_cacciatori.basedinformant.ui

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The stored HTML in a hardened WebView (03-rendering.md section 3), treated as untrusted: JavaScript, file and content
 * access and DOM storage off, every request blocked except the initial in-memory content, no mixed content, and link
 * taps intercepted (`https` opens the system browser, anything else is ignored).
 */
@Composable
actual fun EmailedHtml(html: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                with(settings) {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    blockNetworkLoads = true
                    blockNetworkImage = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setGeolocationEnabled(false)
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        if (url.scheme == "https" && !url.host.isNullOrEmpty()) {
                            view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        return true
                    }

                    // Nothing is fetched: only the in-memory page itself (a data: URL) loads; every other request gets nothing.
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (request.url.scheme == "data") null else WebResourceResponse("text/plain", "utf-8", null)
                }
            }
        },
        update = { it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
    )
}
