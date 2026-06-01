package com.autovice.reader.ui.reader

import android.webkit.JavascriptInterface

class AndroidBridge(private val viewModel: ReaderViewModel) {

    @JavascriptInterface
    fun onUserScroll() {
        viewModel.onUserScrolled()
    }

    @JavascriptInterface
    fun onSegmentTap(spanId: String) {
        viewModel.onSegmentTap(spanId)
    }
}
