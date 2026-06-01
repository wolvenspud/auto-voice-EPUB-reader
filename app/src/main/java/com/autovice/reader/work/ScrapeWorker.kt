package com.autovice.reader.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
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
import com.autovice.scraper.EpubBuilder
import com.autovice.scraper.SyosetuScraper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@HiltWorker
class ScrapeWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val libraryRepository: LibraryRepository,
    private val segmentRepository: SegmentRepository,
    private val voiceRepository: CharacterVoiceRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val novelUrl = inputData.getString(KEY_NOVEL_URL) ?: return@withContext Result.failure()

        try {
            setProgressAsync(workDataOf(KEY_STEP to "Fetching novel info…", KEY_FRACTION to 0.05f))

            val scraper = SyosetuScraper()
            val novel = scraper.scrapeNovel(novelUrl)

            setProgressAsync(workDataOf(KEY_STEP to "Building EPUB…", KEY_FRACTION to 0.4f))

            val tempEpub = File(context.cacheDir, "scraped_${System.currentTimeMillis()}.epub")
            EpubBuilder().build(novel, tempEpub)

            setProgressAsync(workDataOf(KEY_STEP to "Importing…", KEY_FRACTION to 0.5f))

            val bookId = UUID.randomUUID().toString()
            val bookDir = libraryRepository.generateBookDir(bookId).also { it.mkdirs() }
            val epubFile = File(bookDir, "original.epub")
            tempEpub.copyTo(epubFile, overwrite = true)
            tempEpub.delete()

            val parsed = EpubParser().parse(epubFile, bookDir)
            val processedDir = File(bookDir, "processed").also { it.mkdirs() }
            val processor = HtmlProcessor()
            val allSegments = mutableListOf<TtsSegment>()
            val bookIdShort = bookId.replace("-", "").take(8)

            parsed.chapters.forEachIndexed { chapterIndex, chapter ->
                val fraction = 0.5f + (chapterIndex.toFloat() / parsed.chapters.size) * 0.3f
                setProgressAsync(workDataOf(
                    KEY_STEP to "Processing chapter ${chapterIndex + 1}/${parsed.chapters.size}…",
                    KEY_FRACTION to fraction,
                ))

                val htmlContent = chapter.htmlFile.readText(Charsets.UTF_8)
                val baseUrl = "file://${chapter.contentDir.absolutePath}/"
                val result = processor.process(htmlContent, baseUrl, bookId, chapterIndex)

                File(processedDir, "ch_$chapterIndex.html").writeText(result.html, Charsets.UTF_8)

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

            setProgressAsync(workDataOf(KEY_STEP to "Analysing dialogue…", KEY_FRACTION to 0.85f))

            val analyser = DialogueAnalyser()
            val annotatedSegments = allSegments.toMutableList()
            allSegments.groupBy { it.chapterIndex }.forEach { (chapterIndex, chSegs) ->
                val coreSegs = chSegs.map { com.autovice.epub.ProcessedSegment(it.spanId, it.rawText, it.rawText.length) }
                analyser.analyse(coreSegs, chapterIndex, bookId).forEach { ann ->
                    val idx = annotatedSegments.indexOfFirst { it.spanId == ann.spanId }
                    if (idx >= 0) {
                        val tag = SpeakerTag.fromStorageString(ann.speakerTag)
                        val profileId = when (tag) {
                            is SpeakerTag.Narrator -> "${bookIdShort}_narrator"
                            is SpeakerTag.UnknownDialogue -> "${bookIdShort}_unknown_dialogue"
                            is SpeakerTag.InnerMonologue -> "${bookIdShort}_narrator"
                            is SpeakerTag.Group -> "${bookIdShort}_group"
                            is SpeakerTag.Character -> "${bookIdShort}_char_${tag.name.take(8)}"
                        }
                        annotatedSegments[idx] = annotatedSegments[idx].copy(
                            speakerTag = tag,
                            voiceProfileId = profileId,
                            attributionConfidence = ann.confidence,
                        )
                    }
                }
            }

            val systemProfiles = listOf(
                VoiceProfile("${bookIdShort}_narrator", "Narrator", CharacterTier.SYSTEM),
                VoiceProfile("${bookIdShort}_unknown_dialogue", "Unknown Speaker", CharacterTier.SYSTEM),
                VoiceProfile("${bookIdShort}_group", "Group", CharacterTier.SYSTEM),
            )
            voiceRepository.saveProfiles(bookId, systemProfiles)
            segmentRepository.insertAll(annotatedSegments)

            val book = Book(
                id = bookId,
                title = novel.title,
                author = novel.author,
                language = "ja",
                coverPath = null,
                epubPath = epubFile.absolutePath,
                contentPath = bookDir.absolutePath,
                totalChapters = parsed.chapters.size,
                addedAt = System.currentTimeMillis(),
                lastOpenedAt = null,
                source = BookSource.SYOSETU,
                sourceUrl = novelUrl,
            )
            libraryRepository.insertBook(book)

            Result.success(workDataOf(KEY_RESULT_BOOK_ID to bookId))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Scrape failed")))
        }
    }

    companion object {
        const val KEY_NOVEL_URL = "novelUrl"
        const val KEY_STEP = "step"
        const val KEY_FRACTION = "fraction"
        const val KEY_RESULT_BOOK_ID = "resultBookId"
        const val KEY_ERROR = "error"
    }
}
