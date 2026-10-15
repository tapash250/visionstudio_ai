package com.visionstudio.core.data.media

import com.visionstudio.core.common.dispatchers.DefaultDispatcher
import com.visionstudio.core.database.GenerationHistoryDao
import com.visionstudio.core.database.toEntity
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import com.visionstudio.core.network.engine.EngineOutput
import com.visionstudio.core.network.engine.GenerationEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Turns a provider output into a stored, inspected, history-recorded [MediaItem]. */
interface MediaSink {
    suspend fun store(
        engine: GenerationEngine,
        output: EngineOutput,
        slot: EngineSlot,
        taskId: String,
        parameters: GenerationParameters,
        createdAt: Long,
    ): MediaItem
}

/**
 * Downloads [EngineOutput]s into app-specific storage, inspects them (dimensions, duration,
 * BlurHash) and records them in Room. A failed download or an undecodable file is deleted so
 * no half-written media is ever left behind.
 *
 * Shared by image generation and the video worker, so both persist identically.
 */
@Singleton
class MediaPersister @Inject constructor(
    private val dao: GenerationHistoryDao,
    private val files: MediaFileStore,
    private val inspector: MediaInspector,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) : MediaSink {

    override suspend fun store(
        engine: GenerationEngine,
        output: EngineOutput,
        slot: EngineSlot,
        taskId: String,
        parameters: GenerationParameters,
        createdAt: Long,
    ): MediaItem {
        val id = UUID.randomUUID().toString()
        val type = if (output.isVideo) MediaType.VIDEO else MediaType.IMAGE
        val file = files.newFile(id, extensionFor(output.mimeType, type))
        val size = try {
            engine.downloadTo(output, file)
        } catch (failure: Exception) {
            withContext(NonCancellable) { file.delete() }
            throw failure
        }
        val facts = try {
            when (type) {
                MediaType.IMAGE -> inspector.inspectImage(file)
                MediaType.VIDEO -> {
                    val expected = parameters.aspectRatio.dimensionsFor(longEdge = VIDEO_LONG_EDGE, alignment = VIDEO_ALIGNMENT)
                    inspector.inspectVideo(
                        file,
                        expected.width,
                        expected.height,
                        (parameters.durationSeconds ?: DEFAULT_VIDEO_SECONDS) * MILLIS_PER_SECOND,
                    )
                }
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) { file.delete() }
            throw failure
        }
        val item = MediaItem(
            id = id,
            taskId = taskId,
            type = type,
            localUri = files.uriOf(file),
            mimeType = output.mimeType,
            width = facts.width,
            height = facts.height,
            durationMillis = if (type == MediaType.VIDEO) facts.durationMillis else null,
            sizeBytes = size,
            blurHash = facts.blurHash,
            parameters = parameters,
            createdAtEpochMillis = createdAt,
        )
        dao.upsert(withContext(default) { item.toEntity(slot) })
        return item
    }

    private fun extensionFor(mime: String, type: MediaType): String = when (mime) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        else -> type.fileExtension
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val VIDEO_LONG_EDGE = 1280
        const val VIDEO_ALIGNMENT = 16
        const val DEFAULT_VIDEO_SECONDS = 5
    }
}
