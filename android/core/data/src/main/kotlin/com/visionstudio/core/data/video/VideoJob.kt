package com.visionstudio.core.data.video

import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.network.engine.RemoteJob

enum class VideoJobStatus {
    /** Saved, waiting for the worker to start. */
    QUEUED,

    /** The worker is sending the request to the provider. */
    SUBMITTING,

    /** The provider accepted the job; we poll its handle until it finishes. */
    POLLING,

    /** The provider finished; the file is being fetched into app storage. */
    DOWNLOADING,

    COMPLETED,
    FAILED,
    CANCELLED,
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
    val isActive: Boolean get() = !isTerminal

    companion object {
        fun of(name: String): VideoJobStatus = entries.firstOrNull { it.name == name } ?: FAILED
    }
}

/** A video generation request and its live state, as the UI sees it. */
data class VideoJob(
    val id: String,
    val status: VideoJobStatus,
    val parameters: GenerationParameters,
    /** 0..1 when the provider reports it, otherwise null (indeterminate). */
    val progress: Float?,
    val message: String?,
    val error: String?,
    /** Id of the saved MediaItem once [status] is COMPLETED. */
    val resultItemId: String?,
    val hasSourceImage: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/**
 * A job as it was when the user cancelled it: [remote] is the provider's handle if it had one,
 * and [engineFingerprint] identifies the engine that issued it.
 */
data class CancelledJob(
    val id: String,
    val remote: RemoteJob?,
    val engineFingerprint: String?,
)
