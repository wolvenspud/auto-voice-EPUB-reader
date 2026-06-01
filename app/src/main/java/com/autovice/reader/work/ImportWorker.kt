package com.autovice.reader.work

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.autovice.dialogue.DialogueAnalyser
import com.autovice.epub.EpubParser
import com.autovice.epub.HtmlProcessor
import com.autovice.reader.data.db.CharacterVoiceEntity
import com.autovice.reader.data.repository.CharacterVoiceRepository
import com.autovice.reader.data.repository.LibraryRepository
import com.autovice.reader.data.repository.SegmentRepository
import com.autovice.reader.domain.model.AttributionSource
import com.autovice.reader.domain.model.Book
import com.autovice.reader.domain.model.BookSource
import com.autovice.reader.domain.model.CharacterTier
import com.autovice.reader.domain.model.SpeakerTag
import com.autovice.reader.domain.model.TtsSegment
import com.autovice.reader.domain.model.VoiceProfile
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val libraryRepository: LibraryRepository,
    private val segmentRepository: SegmentRepository,
    private val voiceRepository: CharacterVoiceRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return@withContext Result.failure()
        val epubUriString = inputData.getString(KEY_EPUB_URI) ?: return@withContext Result.failure()
        val bookTitleHint = inputData.getString(KEY_BOOK_TITLE)

        try {
            val bookDir = libraryRepository.generateBookDir(bookId)
            bookDir.mkdirs()

            setProgressAsync(workDataOf(KEY_PROGRESS_STEP to "Copying file…", KEY_PROGRESS_FRACTION to 0.05f))

            val epubFile = File(bookDir, "original.epub")
            copyUriToFile(Uri.parse(epubUriString), epubFile)

            setProgressAsync(workDataOf(KEY_PROGRESS_STEP to "Parsing EPUB…", KEY_PROGRESS_FRACTION to 0.15f))

            val parser = EpubParser()
            val parsed = parser.parse(epubFile, bookDir)

            val processedDir = File(bookDir, "processed").also { it.mkdirs() }
            val processor = HtmlProcessor()
            val allSegments = mutableListOf<TtsSegment>()
            val bookIdShort = bookId.replace("-", "").take(8)

            parsed.chapters.forEachIndexed { chapterIndex, chapter ->
                val fraction = 0.15f + (chapterIndex.toFloat() / parsed.chapters.size) * 0.55f
                setProgressAsync(workDataOf(
                    KEY_PROGRESS_STEP to "Processing chapter ${chapterIndex + 1}/${parsed.chapters.size}…",
                    KEY_PROGRESS_FRACTION to fraction,
                ))

                val htmlContent = readHtmlWithEncodingDetection(chapter.htmlFile)
                val baseUrl = "file://${chapter.contentDir.absolutePath}/"
                val result = processor.process(htmlContent, baseUrl, bookId, chapterIndex)

                val outHtml = File(processedDir, "ch_$chapterIndex.html")
                outHtml.writeText(result.html, Charsets.UTF_8)

                result.segments.forEachIndexed { segIndex, seg ->
                    allSegments.add(
                        TtsSegment(
                            spanId = seg.spanId,
                            bookId = bookId,
                            chapterIndex = chapterIndex,
                            segmentIndex = segIndex,
                            rawText = seg.ttsText,
                            speakerTag = SpeakerTag.Narrator,
                            voiceProfileId = "${bookIdShort}_narrator",
                            attributionSource = AttributionSource.HEURISTIC,
                            attributionConfidence = 0.9f,
                        )
                    )
                }
            }

            setProgressAsync(workDataOf(KEY_PROGRESS_STEP to "Analysing dialogue…", KEY_PROGRESS_FRACTION to 0.75f))

            val analyser = DialogueAnalyser()
            val segmentsByChapter = allSegments.groupBy { it.chapterIndex }
            val annotatedSegments = allSegments.toMutableList()

            segmentsByChapter.forEach { (chapterIndex, chapterSegments) ->
                val coreSegments = chapterSegments.map {
                    com.autovice.epub.ProcessedSegment(it.spanId, it.rawText, it.rawText.length)
                }
                val annotations = analyser.analyse(coreSegments, chapterIndex, bookId)
                annotations.forEachIndexed { i, annotation ->
                    val globalIdx = annotatedSegments.indexOfFirst { it.spanId == annotation.spanId }
                    if (globalIdx >= 0) {
                        val speakerTag = SpeakerTag.fromStorageString(annotation.speakerTag)
                        val voiceProfileId = when (speakerTag) {
                            is SpeakerTag.Narrator -> "${bookIdShort}_narrator"
                            is SpeakerTag.UnknownDialogue -> "${bookIdShort}_unknown_dialogue"
                            is SpeakerTag.InnerMonologue -> "${bookIdShort}_narrator"
                            is SpeakerTag.Group -> "${bookIdShort}_group"
                            is SpeakerTag.Character -> "${bookIdShort}_char_${speakerTag.name.take(8)}"
                        }
                        annotatedSegments[globalIdx] = annotatedSegments[globalIdx].copy(
                            speakerTag = speakerTag,
                            voiceProfileId = voiceProfileId,
                            attributionSource = AttributionSource.HEURISTIC,
                            attributionConfidence = annotation.confidence,
                        )
                    }
                }
            }

            setProgressAsync(workDataOf(KEY_PROGRESS_STEP to "Saving to database…", KEY_PROGRESS_FRACTION to 0.85f))

            val systemProfiles = buildSystemProfiles(bookIdShort, bookId)
            voiceRepository.saveProfiles(bookId, systemProfiles)
            segmentRepository.insertAll(annotatedSegments)

            val title = bookTitleHint?.takeIf { it.isNotBlank() } ?: parsed.title
            val book = Book(
                id = bookId,
                title = title,
                author = parsed.author ?: "",
                language = parsed.language ?: "ja",
                coverPath = parsed.coverImagePath,
                epubPath = epubFile.absolutePath,
                contentPath = bookDir.absolutePath,
                totalChapters = parsed.chapters.size,
                addedAt = System.currentTimeMillis(),
                lastOpenedAt = null,
                source = BookSource.LOCAL,
                sourceUrl = null,
            )
            libraryRepository.insertBook(book)

            setProgressAsync(workDataOf(KEY_PROGRESS_STEP to "Done", KEY_PROGRESS_FRACTION to 1.0f))
            Result.success(workDataOf(KEY_RESULT_BOOK_ID to bookId))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "Unknown error")))
        }
    }

    private fun copyUriToFile(uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not open input stream for URI: $uri")
    }

    private fun readHtmlWithEncodingDetection(file: File): String {
        val bytes = file.readBytes()
        val peek = String(bytes.take(1024).toByteArray(), Charsets.ISO_8859_1)
        val charsetMatch = Regex("""charset=["']?([^"'\s;>]+)""", RegexOption.IGNORE_CASE)
            .find(peek)?.groupValues?.getOrNull(1)?.lowercase()
        val cs = when {
            charsetMatch?.contains("shift") == true || charsetMatch?.contains("sjis") == true -> Charsets.ISO_8859_1
            charsetMatch?.contains("euc-jp") == true -> try { charset("EUC-JP") } catch (_: Exception) { Charsets.UTF_8 }
            else -> Charsets.UTF_8
        }
        return String(bytes, cs)
    }

    private fun buildSystemProfiles(bookIdShort: String, bookId: String): List<VoiceProfile> = listOf(
        VoiceProfile(
            profileId = "${bookIdShort}_narrator",
            characterName = "Narrator",
            tier = CharacterTier.SYSTEM,
        ),
        VoiceProfile(
            profileId = "${bookIdShort}_unknown_dialogue",
            characterName = "Unknown Speaker",
            tier = CharacterTier.SYSTEM,
        ),
        VoiceProfile(
            profileId = "${bookIdShort}_group",
            characterName = "Group",
            tier = CharacterTier.SYSTEM,
        ),
    )

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_EPUB_URI = "epubUri"
        const val KEY_BOOK_TITLE = "bookTitle"
        const val KEY_PROGRESS_STEP = "progressStep"
        const val KEY_PROGRESS_FRACTION = "progressFraction"
        const val KEY_RESULT_BOOK_ID = "resultBookId"
        const val KEY_ERROR_MESSAGE = "errorMessage"
    }
}
