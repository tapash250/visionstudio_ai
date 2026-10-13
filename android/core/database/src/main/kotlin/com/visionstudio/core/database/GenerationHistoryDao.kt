package com.visionstudio.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface GenerationHistoryDao {

    @Query("SELECT * FROM generation_history ORDER BY created_at DESC")
    fun observeAll(): Flow<List<GenerationHistoryEntity>>

    @Query("SELECT * FROM generation_history WHERE slot = :slot ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(slot: String, limit: Int): Flow<List<GenerationHistoryEntity>>

    @Query("SELECT * FROM generation_history WHERE media_type = :mediaType ORDER BY created_at DESC")
    fun observeByMediaType(mediaType: String): Flow<List<GenerationHistoryEntity>>

    @Query("SELECT * FROM generation_history WHERE id = :id")
    suspend fun get(id: String): GenerationHistoryEntity?

    @Query("SELECT * FROM generation_history WHERE id = :id")
    fun observe(id: String): Flow<GenerationHistoryEntity?>

    @Upsert
    suspend fun upsert(entity: GenerationHistoryEntity)

    @Upsert
    suspend fun upsertAll(entities: List<GenerationHistoryEntity>)

    @Query("UPDATE generation_history SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean): Int

    @Query("DELETE FROM generation_history WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT COUNT(*) FROM generation_history")
    fun observeCount(): Flow<Int>
}
