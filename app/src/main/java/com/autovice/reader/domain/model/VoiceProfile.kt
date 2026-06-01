package com.autovice.reader.domain.model

data class VoiceProfile(
    val profileId: String,
    val characterName: String,
    val tier: CharacterTier,
    /**
     * Synthesis-time pitch multiplier (Android TTS range: 0.1–2.0).
     * Kept neutral (1.0) for the narrator; varied per character.
     * Note: user playback speed is handled by ExoPlayer, NOT by this value.
     */
    val pitch: Float = 1.0f,
    /**
     * Synthesis-time speed multiplier.
     * Baked into the audio file at synthesis time.
     * Distinct from user-selected playback speed (ExoPlayer layer).
     */
    val synthesisSpeed: Float = 1.0f,
    val voiceEngineId: String = VoiceEngineId.ANDROID_TTS,
    /** Engine-specific voice identifier (e.g. ElevenLabs voice ID). */
    val externalVoiceId: String? = null,
    val estimatedGender: Gender? = null,
    val appearanceCount: Int = 0,
)

enum class CharacterTier {
    /** User-configured, appears frequently. */
    MAJOR,
    /** Auto-assigned from voice pool, appears occasionally. */
    MINOR,
    /** One-off speaker, cycles through generic pool. */
    ONE_OFF,
    /** System profiles: narrator, group — always present per book. */
    SYSTEM,
}

enum class Gender { MALE, FEMALE }

object VoiceEngineId {
    const val ANDROID_TTS = "android_tts"
    const val ELEVEN_LABS = "eleven_labs"
    const val AZURE = "azure"
    const val OLLAMA = "ollama"
}
