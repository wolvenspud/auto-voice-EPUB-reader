package com.autovice.reader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.autovice.reader.domain.model.Book
import com.autovice.reader.domain.model.BookSource

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val language: String,
    val coverPath: String?,
    val epubPath: String,
    val contentPath: String,
    val totalChapters: Int,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val source: String,
    val sourceUrl: String?,
) {
    fun toDomain() = Book(
        id = id,
        title = title,
        author = author,
        language = language,
        coverPath = coverPath,
        epubPath = epubPath,
        contentPath = contentPath,
        totalChapters = totalChapters,
        addedAt = addedAt,
        lastOpenedAt = lastOpenedAt,
        source = BookSource.valueOf(source),
        sourceUrl = sourceUrl,
    )

    companion object {
        fun fromDomain(book: Book) = BookEntity(
            id = book.id,
            title = book.title,
            author = book.author,
            language = book.language,
            coverPath = book.coverPath,
            epubPath = book.epubPath,
            contentPath = book.contentPath,
            totalChapters = book.totalChapters,
            addedAt = book.addedAt,
            lastOpenedAt = book.lastOpenedAt,
            source = book.source.name,
            sourceUrl = book.sourceUrl,
        )
    }
}
