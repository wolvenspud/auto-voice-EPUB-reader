package com.autovice.reader.data.repository

import android.content.Context
import com.autovice.reader.data.db.BookDao
import com.autovice.reader.data.db.BookEntity
import com.autovice.reader.data.db.ReadingProgressDao
import com.autovice.reader.domain.model.Book
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    @ApplicationContext private val context: Context,
) {

    fun observeBooks(): Flow<List<Book>> =
        bookDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getBook(id: String): Book? =
        bookDao.getById(id)?.toDomain()

    suspend fun insertBook(book: Book) =
        bookDao.insert(BookEntity.fromDomain(book))

    suspend fun deleteBook(id: String) {
        bookDao.deleteById(id)
        withContext(Dispatchers.IO) {
            generateBookDir(id).deleteRecursively()
        }
    }

    suspend fun updateLastOpened(id: String) =
        bookDao.updateLastOpened(id, System.currentTimeMillis())

    fun generateBookDir(bookId: String): File =
        File(context.filesDir, "epubs/$bookId")
}
