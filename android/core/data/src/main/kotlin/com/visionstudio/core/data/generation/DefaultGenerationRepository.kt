package com.visionstudio.core.data.generation

import com.visionstudio.core.common.dispatchers.DefaultDispatcher
import com.visionstudio.core.common.dispatchers.IoDispatcher
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.result.getOrThrow
import com.visionstudio.core.data.media.MediaFileStore
import com.visionstudio.core.data.media.MediaSink
import com.visionstudio.core.database.GenerationHistoryDao
import com.visionstudio.core.database.toMediaItem
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import com.visionstudio.core.network.engine.BinaryPayload
import com.visionstudio.core.network.engine.EngineFactory
import com.visionstudio.core.network.engine.EngineOutput
import com.visionstudio.core.network.engine.EngineRequest
import com.visionstudio.core.network.engine.GenerationEngine
import com.visionstudio.core.network.engine.JobStatus
import com.visionstudio.core.network.engine.RemoteJob
import com.visionstudio.core.network.engine.Submission
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultGenerationRepository @Inject constructor(
    private val engineFactory: EngineFactory,
    private val dao: GenerationHistoryDao,
    private val files: MediaFileStore,
    private val sink: MediaSink,
    @IoDispatcher private val io: CoroutineDispatcher,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) : GenerationRepository {

    override fun generate(
        slot: EngineSlot,
        parameters: GenerationParameters,
        sourceImage: BinaryPayload?,
        mask: BinaryPayload?,
    ): Flow<GenerationEvent> = channelFlow {
        val engine = engineFactory.forSlot(slot).getOrThrow()
        try {
            val taskId = UUID.randomUUID().toString()
            send(GenerationEvent.Started(taskId))
            send(GenerationEvent.Progress(fraction = null, message = "Submitting"))

            val request = EngineRequest(slot, parameters, sourceImage, mask)
            val submission = engine.submit(request) { update ->
                // Called from I/O threads; never block the provider stream on a slow collector.
                trySend(GenerationEvent.Progress(update.fraction, update.message))
                update.preview?.let { trySend(GenerationEvent.Preview(it)) }
            }
            val outputs = when (submission) {
                is Submission.Completed -> submission.outputs
                is Submission.Queued -> pollUntilDone(engine, submission.job, engine.config.requestTimeoutSeconds)
            }
            if (outputs.isEmpty()) throw VisionStudioException.ProtocolException(null, "Provider returned no media")

            send(GenerationEvent.Progress(fraction = 1f, message = "Saving"))
            val createdAt = System.currentTimeMillis()
            val items = outputs.take(MAX_OUTPUTS_PER_TASK).mapIndexed { index, output ->
                sink.store(engine, output, slot, taskId, parameters, createdAt + index)
            }
            send(GenerationEvent.Completed(items))
        } finally {
            withContext(NonCancellable) { engine.close() }
        }
    }.flowOn(io)

    /**
     * Polls a queued job with a gentle exponential backoff (1 s → 4 s for images). The total
     * budget is the slot's request timeout, with a floor of [MIN_POLL_BUDGET_MILLIS] so
     * cold-starting GPU queues are not abandoned early.
     */
    private suspend fun ProducerScope<GenerationEvent>.pollUntilDone(
        engine: GenerationEngine,
        job: RemoteJob,
        timeoutSeconds: Int,
    ): List<EngineOutput> {
        val deadline = System.currentTimeMillis() + maxOf(timeoutSeconds * MILLIS_PER_SECOND, MIN_POLL_BUDGET_MILLIS)
        var interval = INITIAL_POLL_MILLIS
        while (true) {
            delay(interval)
            when (val status = engine.poll(job)) {
                is JobStatus.Completed -> return status.outputs
                is JobStatus.Failed -> throw VisionStudioException.ProviderException(null, status.reason)
                is JobStatus.InProgress -> send(
                    GenerationEvent.Progress(
                        fraction = status.progress,
                        message = status.message,
                        queuePosition = status.queuePosition,
                    ),
                )
            }
            if (System.currentTimeMillis() > deadline) {
                throw VisionStudioException.TimeoutException("Job ${job.id} did not finish in time")
            }
            interval = (interval * BACKOFF_FACTOR).toLong().coerceAtMost(MAX_POLL_MILLIS)
        }
    }

    override fun observeRecent(slot: EngineSlot, limit: Int): Flow<List<MediaItem>> =
        dao.observeRecent(slot.name, limit).map { rows -> rows.map { it.toMediaItem() } }.flowOn(default)

    override fun observeLibrary(type: MediaType?, limit: Int): Flow<List<MediaItem>> {
        require(limit > 0) { "limit must be positive" }
        val rows = if (type == null) dao.observeNewest(limit) else dao.observeNewestOfType(type.name, limit)
        return rows.map { page -> page.map { it.toMediaItem() } }.flowOn(default)
    }

    override fun observeCounts(): Flow<MediaCounts> =
        dao.observeCountsByType()
            .map { rows ->
                val byType = rows.associate { it.mediaType to it.count }
                MediaCounts(images = byType[MediaType.IMAGE.name] ?: 0, videos = byType[MediaType.VIDEO.name] ?: 0)
            }
            .distinctUntilChanged()
            .flowOn(default)

    override fun observeItem(id: String): Flow<MediaItem?> =
        dao.observe(id).map { it?.toMediaItem() }.distinctUntilChanged().flowOn(default)

    override suspend fun delete(item: MediaItem) {
        dao.delete(item.id)
        files.delete(item.localUri)
    }

    private companion object {
        const val MAX_OUTPUTS_PER_TASK = 4
        const val MILLIS_PER_SECOND = 1_000L
        const val INITIAL_POLL_MILLIS = 1_000L
        const val MAX_POLL_MILLIS = 4_000L
        const val BACKOFF_FACTOR = 1.5
        const val MIN_POLL_BUDGET_MILLIS = 5 * 60_000L
    }
}
