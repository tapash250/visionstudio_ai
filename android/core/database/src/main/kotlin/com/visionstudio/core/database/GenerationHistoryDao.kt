package com.visionstudio.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface GenerationHistoryDao {

    /**
     * The newest [limit] rows, newest first. `id` breaks ties between rows created in the same
     * millisecond, so the order is total and a growing [limit] never reorders rows already shown.
     */
    @Query("SELECT * FROM generation_history ORDER BY created_at DESC, id DESC LIMIT :limit")
    fun observeNewest(limit: Int): Flow<List<GenerationHistoryEntity>>

    /** As [observeNewest], restricted to one media type. */
    @Query("SELECT * FROM generation_history WHERE media_type = :mediaType ORDER BY created_at DESC, id DESC LIMIT :limit")
    fun observeNewestOfType(mediaType: String, limit: Int): Flow<List<GenerationHistoryEntity>>

    @Query("SELECT * FROM generation_history WHERE slot = :slot ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(slot: String, limit: Int): Flow<List<GenerationHistoryEntity>>

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

    /** How many rows exist per media type. Types with no rows are absent. */
    @Query("SELECT media_type AS mediaType, COUNT(*) AS count FROM generation_history GROUP BY media_type")
    fun observeCountsByType(): Flow<List<MediaTypeCount>>
}

/** One row of [GenerationHistoryDao.observeCountsByType]. */
data class MediaTypeCount(val mediaType: String, val count: Int)
