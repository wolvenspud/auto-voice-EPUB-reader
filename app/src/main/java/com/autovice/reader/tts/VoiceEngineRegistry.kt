package com.autovice.reader.tts

import com.autovice.reader.domain.model.VoiceEngineId

/**
 * Resolves a [VoiceEngine] by [VoiceProfile.voiceEngineId][com.autovice.reader.domain.model.VoiceProfile.voiceEngineId].
 * This is the seam that makes TTS modular: the synthesis worker dispatches per segment,
 * and new engines (cloud, on-device VOICEVOX, …) drop in by being added to the map in
 * [com.autovice.reader.di.TtsModule]. Unknown ids fall back to the always-present Android TTS.
 */
class VoiceEngineRegistry(
    private val engines: Map<String, VoiceEngine>,
) {
    val engineIds: Set<String> get() = engines.keys

    fun engineFor(voiceEngineId: String): VoiceEngine =
        engines[voiceEngineId] ?: engines.getValue(VoiceEngineId.ANDROID_TTS)

    fun engineOrNull(voiceEngineId: String): VoiceEngine? = engines[voiceEngineId]
}
