package com.autovice.reader.domain.model

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val language: String,
    val coverPath: String?,
    val epubPath: String,
    val contentPath: String,
    val totalChapters: Int,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val source: BookSource,
    val sourceUrl: String?,
)

enum class BookSource {
    LOCAL,
    TEXT,
    SYOSETU,
    KAKUYOMU,
    AOZORA,
}
