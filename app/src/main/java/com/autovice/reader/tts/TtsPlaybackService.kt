package com.autovice.reader.tts

import android.content.Intent
import android.os.IBinder
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.autovice.reader.data.repository.SegmentRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TtsPlaybackService : MediaSessionService() {

    @Inject
    lateinit var segmentRepository: SegmentRepository

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _currentSpanId = MutableStateFlow<String?>(null)
    val currentSpanId: StateFlow<String?> = _currentSpanId.asStateFlow()

    private var positionPollingJob: Job? = null
    private var currentBookId: String? = null
    private var currentChapterIndex: Int = 0

    override fun onCreate() {
        super.onCreate()

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()

        startPositionPolling()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onDestroy() {
        positionPollingJob?.cancel()
        serviceScope.cancel()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    private fun startPositionPolling() {
        positionPollingJob = serviceScope.launch {
            while (isActive) {
                delay(100)
                if (player.isPlaying) {
                    val positionMs = player.currentPosition
                    val bookId = currentBookId ?: continue
                    val segment = segmentRepository.getSegmentAtPosition(bookId, currentChapterIndex, positionMs)
                    val spanId = segment?.spanId
                    if (spanId != _currentSpanId.value) {
                        _currentSpanId.value = spanId
                    }
                }
            }
        }
    }

    fun setPlaybackContext(bookId: String, chapterIndex: Int) {
        currentBookId = bookId
        currentChapterIndex = chapterIndex
    }
}
