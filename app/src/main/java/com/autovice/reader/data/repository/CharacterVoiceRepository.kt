package com.autovice.reader.data.repository

import com.autovice.reader.data.db.CharacterVoiceDao
import com.autovice.reader.data.db.CharacterVoiceEntity
import com.autovice.reader.data.db.TtsSegmentDao
import com.autovice.reader.domain.model.VoiceProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CharacterVoiceRepository @Inject constructor(
    private val voiceDao: CharacterVoiceDao,
    private val segmentDao: TtsSegmentDao,
) {

    fun observeVoicesForBook(bookId: String): Flow<List<VoiceProfile>> =
        voiceDao.observeForBook(bookId).map { list -> list.map { it.toDomain() } }

    suspend fun getVoicesForBook(bookId: String): List<VoiceProfile> =
        voiceDao.getForBook(bookId).map { it.toDomain() }

    suspend fun getProfile(profileId: String): VoiceProfile? =
        voiceDao.getProfile(profileId)?.toDomain()

    suspend fun saveProfiles(bookId: String, profiles: List<VoiceProfile>) {
        voiceDao.insertAll(profiles.map { CharacterVoiceEntity.fromDomain(it, bookId) })
    }

    /** Inserts profiles that don't yet exist, leaving already-customised profiles untouched. */
    suspend fun addMissingProfiles(bookId: String, profiles: List<VoiceProfile>) {
        voiceDao.insertNewOnly(profiles.map { CharacterVoiceEntity.fromDomain(it, bookId) })
    }

    suspend fun updateProfile(bookId: String, profile: VoiceProfile) {
        voiceDao.update(CharacterVoiceEntity.fromDomain(profile, bookId))
    }

    suspend fun updateProfileAndInvalidate(bookId: String, profile: VoiceProfile) {
        voiceDao.update(CharacterVoiceEntity.fromDomain(profile, bookId))
        segmentDao.markResynthesisNeeded(bookId, profile.profileId)
    }

    suspend fun deleteAllForBook(bookId: String) =
        voiceDao.deleteAllForBook(bookId)
}
