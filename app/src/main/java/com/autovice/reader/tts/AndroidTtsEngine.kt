package com.autovice.reader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.autovice.audio.WavProcessor
import com.autovice.reader.domain.model.VoiceProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class AndroidTtsEngine(private val context: Context) : VoiceEngine {

    private val _state = MutableStateFlow(VoiceEngine.State.UNINITIALISED)
    override val state: StateFlow<VoiceEngine.State> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private val mutex = Mutex()
    private val utteranceCounter = AtomicLong(0)

    override suspend fun initialise(): Boolean = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Boolean>()
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.JAPANESE)
                val langAvailable = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                // Pin one offline voice so every segment shares the same audio format;
                // mixing network + embedded voices yields incompatible WAVs that can't be concatenated.
                if (langAvailable) tts?.let { pinStableOfflineVoice(it) }
                _state.value = if (langAvailable) VoiceEngine.State.READY else VoiceEngine.State.ERROR
                deferred.complete(langAvailable)
            } else {
                _state.value = VoiceEngine.State.ERROR
                deferred.complete(false)
            }
        }
        deferred.await()
    }

    /**
     * Selects a deterministic, installed, offline Japanese voice and pins it for the session.
     * Without this, the engine may serve some segments from a network voice and others from an
     * embedded one, producing WAVs with differing sample rates that fail concatenation.
     */
    private fun pinStableOfflineVoice(engine: TextToSpeech) {
        runCatching {
            val candidates = engine.voices?.filter { v ->
                v.locale.language == Locale.JAPANESE.language &&
                    !v.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features
            }.orEmpty()
            val chosen = candidates.maxWithOrNull(compareBy({ it.quality }, { it.name }))
            chosen?.let { engine.voice = it }
        }
    }

    override suspend fun synthesiseToFile(
        text: String,
        voiceProfile: VoiceProfile,
        outputFile: File,
    ): Long = mutex.withLock {
        val engine = tts ?: return -1L
        _state.value = VoiceEngine.State.BUSY

        try {
            val result = if (text.length > 3800) {
                synthesiseLongText(engine, text, voiceProfile, outputFile)
            } else {
                synthesiseSingleSegment(engine, text, voiceProfile, outputFile)
            }
            result
        } finally {
            _state.value = VoiceEngine.State.READY
        }
    }

    private suspend fun synthesiseSingleSegment(
        engine: TextToSpeech,
        text: String,
        profile: VoiceProfile,
        outputFile: File,
    ): Long = withContext(Dispatchers.Main) {
        engine.setPitch(profile.pitch)
        engine.setSpeechRate(profile.synthesisSpeed)

        val utteranceId = "utt_${utteranceCounter.incrementAndGet()}"
        val deferred = CompletableDeferred<Boolean>()

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId) deferred.complete(true)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) deferred.complete(false)
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == utteranceId) deferred.complete(false)
            }
        })

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        engine.synthesizeToFile(text, params, outputFile, utteranceId)

        val success = deferred.await()
        if (!success || !outputFile.exists()) return@withContext -1L

        val info = WavProcessor.readInfo(outputFile)
        WavProcessor.durationMs(info)
    }

    private suspend fun synthesiseLongText(
        engine: TextToSpeech,
        text: String,
        profile: VoiceProfile,
        outputFile: File,
    ): Long {
        val chunks = text.chunked(3800)
        val tempFiles = mutableListOf<File>()
        var totalDuration = 0L

        try {
            chunks.forEachIndexed { i, chunk ->
                val temp = File(outputFile.parent, "tmp_chunk_$i.wav")
                val duration = synthesiseSingleSegment(engine, chunk, profile, temp)
                if (duration < 0) return -1L
                tempFiles.add(temp)
                totalDuration += duration
            }

            val pcmData = WavProcessor.concatenate(tempFiles)
            val firstInfo = WavProcessor.readInfo(tempFiles.first())
            val header = WavProcessor.buildWavHeader(
                firstInfo.sampleRate, firstInfo.channels, firstInfo.bitsPerSample, pcmData.size
            )
            outputFile.writeBytes(header + pcmData)
        } finally {
            tempFiles.forEach { it.delete() }
        }
        return totalDuration
    }

    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        _state.value = VoiceEngine.State.UNINITIALISED
    }
}
