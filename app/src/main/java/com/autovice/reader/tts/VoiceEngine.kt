package com.autovice.reader.tts

import com.autovice.reader.domain.model.VoiceProfile
import kotlinx.coroutines.flow.StateFlow
import java.io.File

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
}
