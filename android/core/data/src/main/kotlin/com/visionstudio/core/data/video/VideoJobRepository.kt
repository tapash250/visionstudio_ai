package com.visionstudio.core.data.video

import android.content.Context
import com.visionstudio.core.common.dispatchers.DefaultDispatcher
import com.visionstudio.core.common.dispatchers.IoDispatcher
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.database.VideoJobDao
import com.visionstudio.core.database.VideoJobEntity
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.network.engine.BinaryPayload
import com.visionstudio.core.network.engine.RemoteJob
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Durable store of video jobs. The scheduler enqueues work for what this creates. */
interface VideoJobRepository {
    /** Persists a new QUEUED job and stages its optional source image. Does not start any work. */
    suspend fun create(parameters: GenerationParameters, sourceImage: BinaryPayload?): VideoJob

    fun observeJobs(limit: Int = DEFAULT_LIMIT): Flow<List<VideoJob>>

    suspend fun get(id: String): VideoJob?

    /**
     * Marks a live job cancelled (it stays retryable) and returns what it held, so the provider can
     * be asked to stop it as well. Null if it had already finished.
     */
    suspend fun cancel(id: String): CancelledJob?

    /** Re-queues a failed or cancelled job from scratch. Returns false if it isn't retryable. */
    suspend fun retry(id: String): Boolean

    /** Records a permanent failure decided outside the processor (for example, retries exhausted). */
    suspend fun fail(id: String, message: String)

    /** Removes a job record and its staged source image. */
    suspend fun dismiss(id: String)

    companion object {
        const val DEFAULT_LIMIT: Int = 50
    }
}

fun interface Clock {
    fun now(): Long
}

internal val JobJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Singleton
internal class DefaultVideoJobRepository @Inject constructor(
    private val dao: VideoJobDao,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    @DefaultDispatcher private val default: CoroutineDispatcher,
    private val clock: Clock,
) : VideoJobRepository {

    private val inputsDir: File by lazy { File(context.filesDir, INPUTS_DIR) }

    override suspend fun create(parameters: GenerationParameters, sourceImage: BinaryPayload?): VideoJob {
        if (sourceImage == null && parameters.sourceImageUri != null) {
            throw VisionStudioException.ValidationException("sourceImage", "Image-to-video needs the source image bytes")
        }
        val id = UUID.randomUUID().toString()
        val sourcePath = sourceImage?.let { payload ->
            withContext(io) {
                inputsDir.mkdirs()
                File(inputsDir, "$id.${payload.fileExtension}").also { it.writeBytes(payload.bytes) }.absolutePath
            }
        }
        val now = clock.now()
        val entity = VideoJobEntity(
            id = id,
            taskId = UUID.randomUUID().toString(),
            status = VideoJobStatus.QUEUED.name,
            parametersJson = JobJson.encodeToString(GenerationParameters.serializer(), parameters),
            sourcePath = sourcePath,
            remoteJobJson = null,
            engineFingerprint = null,
            progress = null,
            message = null,
            errorMessage = null,
            resultItemId = null,
            attempts = 0,
            createdAt = now,
            updatedAt = now,
        )
        try {
            dao.upsert(entity)
        } catch (failure: Exception) {
            sourcePath?.let { withContext(io) { File(it).delete() } }
            throw failure
        }
        return entity.toJob()
    }

    /** Each row's parameters are JSON; decode them on the Default dispatcher, not on whoever collects (usually Main). */
    override fun observeJobs(limit: Int): Flow<List<VideoJob>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toJob() } }.flowOn(default)

    override suspend fun get(id: String): VideoJob? = dao.get(id)?.toJob()

    override suspend fun cancel(id: String): CancelledJob? {
        // The staged source image is kept so a cancelled job can be retried without re-picking.
        val row = dao.cancelLive(id, clock.now()) ?: return null
        return CancelledJob(
            id = row.id,
            remote = row.remoteJobJson?.let { json ->
                runCatching { JobJson.decodeFromString(RemoteJob.serializer(), json) }.getOrNull()
            },
            engineFingerprint = row.engineFingerprint,
        )
    }

    override suspend fun retry(id: String): Boolean {
        val job = dao.get(id) ?: return false
        // The source image is deleted only when a job completes or is dismissed, so failed and
        // cancelled image-to-video jobs can normally retry; if it has gone, the user must start over.
        val path = job.sourcePath
        if (path != null && !withContext(io) { File(path).isFile }) return false
        return dao.resetForRetry(id, clock.now()) > 0
    }

    override suspend fun fail(id: String, message: String) {
        dao.markFailed(id, message, clock.now())
    }

    override suspend fun dismiss(id: String) {
        dao.get(id)?.sourcePath?.let { deleteFile(it) }
        dao.delete(id)
    }

    private suspend fun deleteFile(path: String) {
        withContext(io) { File(path).delete() }
    }

    private companion object {
        const val INPUTS_DIR = "video_inputs"
    }
}

internal fun VideoJobEntity.toJob(): VideoJob = VideoJob(
    id = id,
    status = VideoJobStatus.of(status),
    parameters = runCatching { JobJson.decodeFromString(GenerationParameters.serializer(), parametersJson) }
        .getOrElse { GenerationParameters(prompt = "", modelId = "") },
    progress = progress,
    message = message,
    error = errorMessage,
    resultItemId = resultItemId,
    hasSourceImage = sourcePath != null,
    createdAtEpochMillis = createdAt,
    updatedAtEpochMillis = updatedAt,
)
