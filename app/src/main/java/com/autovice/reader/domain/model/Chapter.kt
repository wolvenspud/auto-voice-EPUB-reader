package com.autovice.reader.domain.model

data class Chapter(
    val index: Int,
    val title: String?,
    /** Absolute path to the WebView-ready processed HTML file. */
    val htmlPath: String,
    /** Absolute path to the synthesised AAC audio file, null if not yet synthesised. */
    val audioPath: String?,
    val charCount: Int,
    val segmentCount: Int,
)
