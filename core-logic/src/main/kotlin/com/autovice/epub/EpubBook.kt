package com.autovice.epub

import java.io.File

data class ParsedEpub(
    val title: String,
    val author: String?,
    val language: String?,
    val coverImagePath: String?,
    val chapters: List<ParsedChapter>,
    val extractedDir: File,
)

data class ParsedChapter(
    val index: Int,
    val title: String?,
    val htmlFile: File,
    val contentDir: File,
)
