package com.visionstudio.core.data.generation

import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.network.engine.BinaryPayload
import kotlinx.coroutines.flow.Flow

/** Lifecycle events of one generation, in order: Started, then Progress*, then Completed. */
sealed interface GenerationEvent {
    data class Started(val taskId: String) : GenerationEvent

    data class Progress(
        val fraction: Float?,
        val message: String?,
        val queuePosition: Int? = null,
    ) : GenerationEvent

    /** Low-resolution intermediate render streamed by the provider. */
    class Preview(val payload: BinaryPayload) : GenerationEvent

    data class Completed(val items: List<MediaItem>) : GenerationEvent
}

interface GenerationRepository {
    /**
     * Cold flow that runs one generation when collected: resolve the slot's engine, submit,
     * stream or poll, download outputs into app storage, inspect them, and persist them to
     * Room. Cancelling the collector cancels the provider request. Failures are thrown as
     * VisionStudioException.
     */
    fun generate(
        slot: EngineSlot,
        parameters: GenerationParameters,
        sourceImage: BinaryPayload? = null,
        mask: BinaryPayload? = null,
    ): Flow<GenerationEvent>

    fun observeRecent(slot: EngineSlot, limit: Int = DEFAULT_RECENT_LIMIT): Flow<List<MediaItem>>

    fun observeAll(): Flow<List<MediaItem>>

    suspend fun delete(item: MediaItem)

    companion object {
        const val DEFAULT_RECENT_LIMIT: Int = 24
    }
}
