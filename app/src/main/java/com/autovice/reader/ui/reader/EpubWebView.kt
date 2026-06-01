package com.autovice.reader.ui.reader

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpubWebView(
    htmlFilePath: String?,
    highlightedSpanId: String?,
    bridge: AndroidBridge,
    onBridgeReady: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(highlightedSpanId, pageLoaded) {
        if (pageLoaded && highlightedSpanId != null) {
            webViewRef?.let { wv ->
                // Mark the scroll as programmatic before calling scrollIntoView
                wv.evaluateJavascript("_markProgScroll()", null)
                wv.evaluateJavascript("highlightSegment('$highlightedSpanId')", null)
            }
        }
    }

    LaunchedEffect(htmlFilePath) {
        pageLoaded = false
        htmlFilePath?.let { path ->
            webViewRef?.loadUrl("file://$path")
        }
    }

    AndroidView(
        factory = { context ->
            WebView(context).also { wv ->
                wv.settings.apply {
                    javaScriptEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    @Suppress("DEPRECATION")
                    allowFileAccess = true
                    @Suppress("DEPRECATION")
                    allowContentAccess = true
                    domStorageEnabled = true
                }
                wv.addJavascriptInterface(bridge, "AndroidBridge")
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        pageLoaded = true
                        view?.let { onBridgeReady(it) }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        // Keep internal file:// navigation within the WebView
                        val url = request?.url?.toString() ?: return false
                        return !url.startsWith("file://")
                    }
                }
                webViewRef = wv
                htmlFilePath?.let { path -> wv.loadUrl("file://$path") }
            }
        },
        update = { wv ->
            webViewRef = wv
        },
        modifier = modifier,
    )
}
