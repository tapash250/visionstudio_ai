package com.visionstudio.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Every mutating query is guarded with `status NOT IN (terminal states)`, so a slow progress
 * update, or a worker that was stopped mid-flight, can never resurrect a job the user has
 * cancelled or one that has already finished.
 */
@Dao
interface VideoJobDao {

    @Query("SELECT * FROM video_jobs WHERE id = :id")
    suspend fun get(id: String): VideoJobEntity?

    @Query("SELECT status FROM video_jobs WHERE id = :id")
    suspend fun statusOf(id: String): String?

    @Query("SELECT * FROM video_jobs ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<VideoJobEntity>>

    @Upsert
    suspend fun upsert(entity: VideoJobEntity)

    @Query(
        "UPDATE video_jobs SET status = :status, progress = :progress, message = :message, updated_at = :now " +
            "WHERE id = :id AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')",
    )
    suspend fun updateProgress(id: String, status: String, progress: Float?, message: String?, now: Long): Int

    @Query(
        "UPDATE video_jobs SET status = 'POLLING', remote_job_json = :remoteJobJson, engine_fingerprint = :fingerprint, " +
            "progress = NULL, message = 'Queued', updated_at = :now " +
            "WHERE id = :id AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')",
    )
    suspend fun setRemoteJob(id: String, remoteJobJson: String, fingerprint: String, now: Long): Int

    @Query(
        "UPDATE video_jobs SET engine_fingerprint = :fingerprint, updated_at = :now " +
            "WHERE id = :id AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')",
    )
    suspend fun setFingerprint(id: String, fingerprint: String, now: Long): Int

    @Query(
        "UPDATE video_jobs SET status = 'COMPLETED', progress = 1.0, message = NULL, error_message = NULL, " +
            "result_item_id = :resultItemId, updated_at = :now " +
            "WHERE id = :id AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')",
    )
    suspend fun markCompleted(id: String, resultItemId: String, now: Long): Int

    @Query(
        "UPDATE video_jobs SET status = 'FAILED', error_message = :error, message = NULL, updated_at = :now " +
            "WHERE id = :id AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')",
    )
    suspend fun markFailed(id: String, error: String, now: Long): Int

    /** A job that already finished or failed is left as it is; only live jobs can be cancelled. */
    @Query(
        "UPDATE video_jobs SET status = 'CANCELLED', message = NULL, updated_at = :now " +
            "WHERE id = :id AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')",
    )
    suspend fun markCancelled(id: String, now: Long): Int

    /**
     * Cancels a live job and returns the row as it was at that moment, provider handle included,
     * so the caller can ask the provider to stop it. Null if it had already finished or doesn't
     * exist. Atomic, so a retry can't clear the handle between reading it and cancelling.
     */
    @Transaction
    suspend fun cancelLive(id: String, now: Long): VideoJobEntity? {
        val row = get(id) ?: return null
        return if (markCancelled(id, now) > 0) row else null
    }

    @Query("UPDATE video_jobs SET attempts = attempts + 1, updated_at = :now WHERE id = :id")
    suspend fun incrementAttempts(id: String, now: Long): Int

    /** Failed or cancelled jobs can be re-queued; the provider handle is dropped so it submits afresh. */
    @Query(
        "UPDATE video_jobs SET status = 'QUEUED', remote_job_json = NULL, engine_fingerprint = NULL, progress = NULL, " +
            "message = NULL, error_message = NULL, attempts = 0, updated_at = :now " +
            "WHERE id = :id AND status IN ('FAILED', 'CANCELLED')",
    )
    suspend fun resetForRetry(id: String, now: Long): Int

    @Query("DELETE FROM video_jobs WHERE id = :id")
    suspend fun delete(id: String): Int
}
