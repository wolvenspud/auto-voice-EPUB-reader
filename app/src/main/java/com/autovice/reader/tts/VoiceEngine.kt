package com.autovice.reader.tts

import com.autovice.reader.domain.model.VoiceProfile
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** One selectable voice an engine offers (e.g. a VOICEVOX style). */
data class EngineVoice(val id: String, val label: String)

interface VoiceEngine {

    enum class State { UNINITIALISED, READY, BUSY, ERROR }

    val state: StateFlow<State>

    suspend fun initialise(): Boolean

    suspend fun synthesiseToFile(
        text: String,
        voiceProfile: VoiceProfile,
        outputFile: File,
    ): Long

    fun release()

    /** Voices this engine can produce, for the per-character picker. Empty = not selectable. */
    suspend fun listVoices(): List<EngineVoice> = emptyList()
}
