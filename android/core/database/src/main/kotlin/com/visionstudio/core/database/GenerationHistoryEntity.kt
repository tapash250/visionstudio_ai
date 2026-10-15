package com.visionstudio.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One generated asset. Prompt, seed and model are denormalised into columns so the Vault can
 * filter and display them without decoding [parametersJson], which holds the full
 * GenerationParameters for exact re-runs.
 */
@Entity(
    tableName = "generation_history",
    indices = [
        Index("created_at"),
        Index("slot", "created_at"),
        Index("media_type", "created_at"),
        Index("task_id"),
    ],
)
data class GenerationHistoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    val slot: String,
    @ColumnInfo(name = "media_type") val mediaType: String,
    @ColumnInfo(name = "local_uri") val localUri: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "duration_millis") val durationMillis: Long?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "blur_hash") val blurHash: String?,
    val prompt: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    val seed: Long?,
    @ColumnInfo(name = "parameters_json") val parametersJson: String,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
)
