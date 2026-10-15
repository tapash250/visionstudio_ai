package com.visionstudio.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable record of one video generation. WorkManager may stop and restart the worker at any
 * time (process death, the 10-minute execution cap, network loss), so everything needed to
 * *resume* lives here: the request parameters, the staged source image, and above all the
 * provider's job handle. A restarted worker that finds [remoteJobJson] polls that job rather
 * than submitting (and paying for) a second one.
 *
 * [status] holds a `VideoJobStatus` name; the enum lives in the data layer.
 */
@Entity(
    tableName = "video_jobs",
    indices = [Index("status"), Index("created_at")],
)
data class VideoJobEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    val status: String,
    @ColumnInfo(name = "parameters_json") val parametersJson: String,
    @ColumnInfo(name = "source_path") val sourcePath: String?,
    @ColumnInfo(name = "remote_job_json") val remoteJobJson: String?,
    @ColumnInfo(name = "engine_fingerprint") val engineFingerprint: String?,
    val progress: Float?,
    val message: String?,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "result_item_id") val resultItemId: String?,
    val attempts: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
