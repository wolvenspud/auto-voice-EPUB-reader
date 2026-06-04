package com.autovice.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterVoiceDao {

    @Query("""
        SELECT * FROM character_voices
        WHERE bookId = :bookId
        ORDER BY
            CASE tier
                WHEN 'SYSTEM' THEN 0
                WHEN 'MAJOR' THEN 1
                WHEN 'MINOR' THEN 2
                ELSE 3
            END,
            appearanceCount DESC
    """)
    fun observeForBook(bookId: String): Flow<List<CharacterVoiceEntity>>

    @Query("""
        SELECT * FROM character_voices
        WHERE bookId = :bookId
        ORDER BY appearanceCount DESC
    """)
    suspend fun getForBook(bookId: String): List<CharacterVoiceEntity>

    @Query("SELECT * FROM character_voices WHERE profileId = :profileId")
    suspend fun getProfile(profileId: String): CharacterVoiceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(voice: CharacterVoiceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(voices: List<CharacterVoiceEntity>)

    /** Adds profiles only when absent, preserving any existing user-tuned rows. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewOnly(voices: List<CharacterVoiceEntity>)

    @Update
    suspend fun update(voice: CharacterVoiceEntity)

    @Query("UPDATE character_voices SET appearanceCount = appearanceCount + 1 WHERE profileId = :profileId")
    suspend fun incrementAppearance(profileId: String)

    @Query("DELETE FROM character_voices WHERE bookId = :bookId")
    suspend fun deleteAllForBook(bookId: String)
}
