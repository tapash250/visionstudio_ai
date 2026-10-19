package com.visionstudio.core.data.video

import com.visionstudio.core.common.dispatchers.IoDispatcher
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.result.getOrThrow
import com.visionstudio.core.data.media.MediaSink
import com.visionstudio.core.database.VideoJobDao
import com.visionstudio.core.database.VideoJobEntity
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.MediaItem
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** Where the processor gets its provider adapter. Overridable so tests can script a provider. */
fun interface EngineSource {
    suspend fun open(slot: EngineSlot): GenerationEngine
}

internal class FactoryEngineSource @Inject constructor(private val factory: EngineFactory) : EngineSource {
    override suspend fun open(slot: EngineSlot): GenerationEngine = factory.forSlot(slot).getOrThrow()
}

/** How one execution of a job ended. */
sealed interface VideoJobOutcome {
    data class Completed(val item: MediaItem) : VideoJobOutcome

    /** Permanent failure; recorded on the job. Do not retry automatically. */
    data class Failed(val message: String) : VideoJobOutcome

    /** Transient trouble (network, provider 5xx). Progress is saved, so a later run resumes. */
    data class Retry(val reason: String) : VideoJobOutcome

    data object Cancelled : VideoJobOutcome

    /** The job was already COMPLETED or FAILED before this run began. */
    data object AlreadyFinished : VideoJobOutcome
}

/**
 * Executes one video job to completion. Designed to be stopped and started at any moment:
 *
 * - **Does not resubmit once the provider has accepted the job.** The provider's job handle is
 *   written to Room the instant it is returned, and a run that finds a handle goes straight to
 *   polling. This is *at-least-once* around the submit call itself: if the app dies, or a read
 *   times out, between the provider accepting the job and us recording the handle, we cannot
 *   know whether a job exists. A timeout is therefore treated as final (the user is told it may
 *   have started and can retry deliberately) rather than silently submitting a second paid job.
 *   Only failures that provably happen before the request is accepted (no route, DNS) retry.
 * - **Polls every 5 s, easing up to 10 s**, until the provider reports done, failed, or the
 *   [MAX_JOB_MILLIS] budget (measured from when the job was created) is spent.
 * - **Honours cancellation** between polls by re-reading the job's status. The provider is told
 *   by [RemoteJobCanceller] when the user cancels; this run tells it only when the job was
 *   accepted after the cancel, since then only this run knows the handle.
 * - **Refuses to poll a stranger's handle**: if the configured engine changed since submission,
 *   the job fails cleanly instead of asking a different provider about an unknown id.
 * - Separates **transient** problems (returned as [VideoJobOutcome.Retry]) from **permanent**
 *   ones (recorded as FAILED).
 */
@Singleton
class VideoJobProcessor @Inject constructor(
    private val dao: VideoJobDao,
    private val engines: EngineSource,
    private val sink: MediaSink,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun run(jobId: String, onProgress: (progress: Float?, message: String?) -> Unit = { _, _ -> }): VideoJobOutcome {
        val row = dao.get(jobId) ?: return VideoJobOutcome.Failed("This job no longer exists.")
        when (VideoJobStatus.of(row.status)) {
            VideoJobStatus.CANCELLED -> return VideoJobOutcome.Cancelled
            VideoJobStatus.COMPLETED, VideoJobStatus.FAILED -> return VideoJobOutcome.AlreadyFinished
            else -> Unit
        }
        dao.incrementAttempts(jobId, clock.now())

        val parameters = try {
            JobJson.decodeFromString(GenerationParameters.serializer(), row.parametersJson)
        } catch (corrupt: IllegalArgumentException) {
            return fail(jobId, "The saved request could not be read.")
        }
        val engine = try {
            engines.open(EngineSlot.VIDEO_STUDIO)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return if (failure is VisionStudioException.ValidationException) {
                fail(jobId, failure.message ?: "The video engine isn't configured.")
            } else {
                classify(jobId, failure)
            }
        }

        return try {
            execute(jobId, row, parameters, engine, onProgress)
        } finally {
            withContext(NonCancellable) { engine.close() }
        }
    }

    private suspend fun execute(
        jobId: String,
        row: VideoJobEntity,
        parameters: GenerationParameters,
        engine: GenerationEngine,
        onProgress: (Float?, String?) -> Unit,
    ): VideoJobOutcome {
        val fingerprint = engine.config.fingerprint()
        val storedHandle = row.remoteJobJson
        var remote: RemoteJob? = storedHandle?.let { decodeRemote(it) }
        if (storedHandle != null && remote == null) {
            // A handle exists but can't be read. Treating that as "never submitted" would pay for a second render.
            return fail(jobId, "This render's saved provider reference is unreadable, so it can't be resumed safely. Start it again.")
        }
        var outputs: List<EngineOutput>? = null
        val resuming = remote != null

        if (remote != null) {
            val submittedWith = row.engineFingerprint
            if (submittedWith != null && submittedWith != fingerprint) {
                return fail(jobId, "The video engine was changed while this job was running. Start it again with the new engine.")
            }
        } else {
            val source = row.sourcePath?.let { path ->
                readSource(path) ?: return fail(jobId, "The source image for this job is no longer available.")
            }
            dao.updateProgress(jobId, VideoJobStatus.SUBMITTING.name, null, "Submitting", clock.now())
            onProgress(null, "Submitting")
            dao.setFingerprint(jobId, fingerprint, clock.now())
            val submission = try {
                engine.submit(EngineRequest(EngineSlot.VIDEO_STUDIO, parameters, source)) { update ->
                    update.fraction?.let { onProgress(it, update.message) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return classifySubmitFailure(jobId, failure)
            }
            when (submission) {
                is Submission.Completed -> outputs = submission.outputs
                is Submission.Queued -> {
                    remote = submission.job
                    // From this point the job survives any restart: the handle is durable. A zero result
                    // means the user cancelled while the provider was accepting it. Only this run has the
                    // new handle, so it is the one that has to ask the provider to drop the job.
                    val saved = dao.setRemoteJob(jobId, JobJson.encodeToString(RemoteJob.serializer(), submission.job), fingerprint, clock.now())
                    if (saved == 0) {
                        requestRemoteCancel(engine, submission.job)
                        return VideoJobOutcome.Cancelled
                    }
                }
            }
        }

        if (outputs == null) {
            when (val polled = poll(jobId, row.createdAt, engine, requireNotNull(remote), JobJson.encodeToString(RemoteJob.serializer(), requireNotNull(remote)), resuming, onProgress)) {
                is Polled.Done -> outputs = polled.outputs
                is Polled.Ended -> return polled.outcome
            }
        }
        return finish(jobId, row, parameters, engine, outputs, onProgress)
    }

    private sealed interface Polled {
        class Done(val outputs: List<EngineOutput>) : Polled
        class Ended(val outcome: VideoJobOutcome) : Polled
    }

    private suspend fun poll(
        jobId: String,
        createdAt: Long,
        engine: GenerationEngine,
        remote: RemoteJob,
        handleJson: String,
        resuming: Boolean,
        onProgress: (Float?, String?) -> Unit,
    ): Polled {
        var interval = POLL_START_MILLIS
        var skipDelay = resuming // a resumed job may already be done; ask right away
        var consecutiveErrors = 0
        while (true) {
            if (!skipDelay) delay(interval)
            skipDelay = false

            if (isSuperseded(jobId, handleJson)) return Polled.Ended(VideoJobOutcome.Cancelled)

            try {
                when (val status = engine.poll(remote)) {
                    is JobStatus.Completed -> {
                        if (isSuperseded(jobId, handleJson)) return Polled.Ended(VideoJobOutcome.Cancelled)
                        return Polled.Done(status.outputs)
                    }
                    is JobStatus.Failed -> {
                        if (isSuperseded(jobId, handleJson)) return Polled.Ended(VideoJobOutcome.Cancelled)
                        return Polled.Ended(fail(jobId, status.reason))
                    }
                    is JobStatus.InProgress -> {
                        // The user may have cancelled and retried while this poll was in flight; re-check
                        // before writing, or this stale run would overwrite the new run's state.
                        if (isSuperseded(jobId, handleJson)) return Polled.Ended(VideoJobOutcome.Cancelled)
                        val message = status.queuePosition?.let { "Queued · position $it" } ?: status.message ?: "Rendering"
                        dao.updateProgress(jobId, VideoJobStatus.POLLING.name, status.progress, message, clock.now())
                        onProgress(status.progress, message)
                    }
                }
                consecutiveErrors = 0
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val outcome = classify(jobId, failure)
                if (outcome !is VideoJobOutcome.Retry) return Polled.Ended(outcome)
                if (++consecutiveErrors >= MAX_CONSECUTIVE_POLL_ERRORS) return Polled.Ended(outcome)
            }

            if (clock.now() - createdAt > MAX_JOB_MILLIS) {
                return Polled.Ended(fail(jobId, "The provider didn't finish this video within ${MAX_JOB_MILLIS / MILLIS_PER_MINUTE} minutes."))
            }
            interval = (interval * BACKOFF).toLong().coerceAtMost(POLL_MAX_MILLIS)
        }
    }

    private suspend fun finish(
        jobId: String,
        row: VideoJobEntity,
        parameters: GenerationParameters,
        engine: GenerationEngine,
        outputs: List<EngineOutput>,
        onProgress: (Float?, String?) -> Unit,
    ): VideoJobOutcome {
        if (outputs.isEmpty()) return fail(jobId, "The provider finished but returned no video.")
        dao.updateProgress(jobId, VideoJobStatus.DOWNLOADING.name, 1f, "Saving video", clock.now())
        onProgress(1f, "Saving video")
        val chosen = outputs.firstOrNull { it.isVideo } ?: outputs.first()
        val item = try {
            sink.store(engine, chosen, EngineSlot.VIDEO_STUDIO, row.taskId, parameters, clock.now())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return classify(jobId, failure)
        }
        if (dao.markCompleted(jobId, item.id, clock.now()) == 0) {
            // The user cancelled while we were downloading. The file stays in the Vault; the job stays cancelled.
            return VideoJobOutcome.Cancelled
        }
        row.sourcePath?.let { path -> withContext(io) { File(path).delete() } }
        return VideoJobOutcome.Completed(item)
    }

    /**
     * True when this run no longer owns the job: it was cancelled, or it was cancelled and retried
     * (so the stored provider handle is gone or is a different one). Without the handle check, a
     * cancel followed quickly by a retry would leave the old run polling and able to complete a job
     * that now belongs to a newer run.
     */
    private suspend fun isSuperseded(jobId: String, handleJson: String): Boolean {
        val row = dao.get(jobId) ?: return true
        if (VideoJobStatus.of(row.status) == VideoJobStatus.CANCELLED) return true
        val current = row.remoteJobJson?.let(::decodeRemote)
        val ours = decodeRemote(handleJson)
        return current != ours
    }

    /**
     * A submit can fail in two very different ways. If the request never left the device (no
     * route to host, DNS) retrying is safe. A timeout is ambiguous: the provider may have accepted
     * the job and be rendering it, so a retry could pay for a second one. That is reported as a
     * final failure the user can choose to retry.
     */
    private suspend fun classifySubmitFailure(jobId: String, failure: Throwable): VideoJobOutcome =
        if (failure is VisionStudioException.TimeoutException) {
            fail(jobId, "The provider didn't answer in time, so this render may have started. Check your provider's dashboard before trying again to avoid being charged twice.")
        } else {
            classify(jobId, failure)
        }

    private suspend fun fail(jobId: String, message: String): VideoJobOutcome {
        dao.markFailed(jobId, message, clock.now())
        return VideoJobOutcome.Failed(message)
    }

    /**
     * Transient failures keep the job alive for a later run; everything else is final. A tool-level
     * error from an MCP server has no HTTP status and is treated as final: repeating the same
     * request would just fail the same way (and might be billed again).
     */
    private suspend fun classify(jobId: String, failure: Throwable): VideoJobOutcome {
        val message = (failure as? VisionStudioException)?.message ?: failure.message ?: "Something went wrong."
        val transient = when (failure) {
            is VisionStudioException.NetworkException, is VisionStudioException.TimeoutException -> true
            is VisionStudioException.ProviderException ->
                failure.httpCode == HTTP_TOO_MANY_REQUESTS || failure.httpCode in HTTP_SERVER_ERRORS
            is VisionStudioException -> false
            is IOException -> true
            else -> false
        }
        return if (transient) VideoJobOutcome.Retry(message) else fail(jobId, message)
    }

    private suspend fun readSource(path: String): BinaryPayload? = withContext(io) {
        val file = File(path)
        if (!file.isFile) return@withContext null
        val mime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }
        BinaryPayload(file.readBytes(), mime)
    }

    private fun decodeRemote(json: String): RemoteJob? =
        runCatching { JobJson.decodeFromString(RemoteJob.serializer(), json) }.getOrNull()

    companion object {
        const val POLL_START_MILLIS: Long = 5_000
        const val POLL_MAX_MILLIS: Long = 10_000
        private const val BACKOFF = 1.25
        const val MAX_JOB_MILLIS: Long = 45 * 60_000L
        const val MAX_CONSECUTIVE_POLL_ERRORS: Int = 5
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val HTTP_SERVER_ERRORS = 500..599
    }
}
