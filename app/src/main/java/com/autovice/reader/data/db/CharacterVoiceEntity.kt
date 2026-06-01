package com.autovice.reader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.autovice.reader.domain.model.CharacterTier
import com.autovice.reader.domain.model.Gender
import com.autovice.reader.domain.model.VoiceEngineId
import com.autovice.reader.domain.model.VoiceProfile

@Entity(
    tableName = "character_voices",
    indices = [Index(value = ["bookId"])]
)
data class CharacterVoiceEntity(
    @PrimaryKey val profileId: String,
    val bookId: String,
    val characterName: String,
    val tier: String,
    val pitch: Float = 1.0f,
    val synthesisSpeed: Float = 1.0f,
    val voiceEngineId: String = VoiceEngineId.ANDROID_TTS,
    val externalVoiceId: String? = null,
    val estimatedGender: String? = null,
    val appearanceCount: Int = 0,
) {
    fun toDomain() = VoiceProfile(
        profileId = profileId,
        characterName = characterName,
        tier = CharacterTier.valueOf(tier),
        pitch = pitch,
        synthesisSpeed = synthesisSpeed,
        voiceEngineId = voiceEngineId,
        externalVoiceId = externalVoiceId,
        estimatedGender = estimatedGender?.let { Gender.valueOf(it) },
        appearanceCount = appearanceCount,
    )

    companion object {
        fun fromDomain(profile: VoiceProfile, bookId: String) = CharacterVoiceEntity(
            profileId = profile.profileId,
            bookId = bookId,
            characterName = profile.characterName,
            tier = profile.tier.name,
            pitch = profile.pitch,
            synthesisSpeed = profile.synthesisSpeed,
            voiceEngineId = profile.voiceEngineId,
            externalVoiceId = profile.externalVoiceId,
            estimatedGender = profile.estimatedGender?.name,
            appearanceCount = profile.appearanceCount,
        )
    }
}
