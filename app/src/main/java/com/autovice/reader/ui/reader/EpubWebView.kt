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

private val CHAPTER_FILE_REGEX = Regex("""/ch_\d+\.html""")

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpubWebView(
    htmlFilePath: String?,
    highlightedSpanId: String?,
    bridge: AndroidBridge,
    bgHex: String,
    textHex: String,
    fontSize: Float,
    lineHeight: Float,
    onBridgeReady: (WebView) -> Unit,
    onInternalLink: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }

    // Apply theme colours / typography to the page. Processed chapters consume the CSS variables,
    // but internal links can reach chapter files that weren't rewritten and so lack our stylesheet —
    // for those, the variables alone do nothing and the page renders default black text over the
    // dark WebView background. So we also inject a forced colour rule that themes *any* page.
    LaunchedEffect(pageLoaded, bgHex, textHex, fontSize, lineHeight) {
        if (pageLoaded) {
            webViewRef?.let { wv ->
                wv.setBackgroundColor(android.graphics.Color.parseColor(bgHex))
                wv.evaluateJavascript(
                    """
                    (function(){
                      var s = document.documentElement.style;
                      s.setProperty('--bg-color', '$bgHex');
                      s.setProperty('--text-color', '$textHex');
                      s.setProperty('--font-size', '${fontSize}px');
                      s.setProperty('--line-height', '$lineHeight');
                      var st = document.getElementById('av-forced-theme');
                      if (!st) {
                        st = document.createElement('style');
                        st.id = 'av-forced-theme';
                        (document.head || document.documentElement).appendChild(st);
                      }
                      st.textContent =
                        'html{background-color:$bgHex;}' +
                        'body{background-color:$bgHex !important;color:$textHex !important;}' +
                        'p,div,span,li,td,th,h1,h2,h3,h4,h5,h6,blockquote{color:$textHex !important;}';
                    })();
                    """.trimIndent(),
                    null,
                )
            }
        }
    }

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
            // Set background before loading so dark-mode pages don't flash white.
            webViewRef?.setBackgroundColor(android.graphics.Color.parseColor(bgHex))
            webViewRef?.loadUrl("file://$path")
        }
    }

    AndroidView(
        factory = { context ->
            WebView(context).also { wv ->
                wv.settings.apply {
                    javaScriptEnabled = true
                    // Render at device width; the EPUB HTML has no <meta viewport>, so a wide
                    // viewport would zoom the page into the top-left corner.
                    loadWithOverviewMode = false
                    useWideViewPort = false
                    @Suppress("DEPRECATION")
                    allowFileAccess = true
                    @Suppress("DEPRECATION")
                    allowContentAccess = true
                    domStorageEnabled = true
                }
                wv.setBackgroundColor(android.graphics.Color.parseColor(bgHex))
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
                        val url = request?.url?.toString() ?: return false
                        if (!url.startsWith("file://")) return true  // block external navigation
                        // Route cross-chapter links through the ViewModel so playback state
                        // stays in sync and dark-mode CSS vars are always applied correctly.
                        if (CHAPTER_FILE_REGEX.containsMatchIn(url)) {
                            onInternalLink(url)
                            return true
                        }
                        return false  // allow same-page anchor scrolling
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
