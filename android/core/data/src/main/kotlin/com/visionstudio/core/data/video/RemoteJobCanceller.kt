package com.visionstudio.core.data.video

import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.network.engine.GenerationEngine
import com.visionstudio.core.network.engine.RemoteCancelResult
import com.visionstudio.core.network.engine.RemoteJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** What came of telling the provider about a cancelled job. Never an error: this is best effort. */
enum class RemoteCancelOutcome {
    REQUESTED,
    ALREADY_FINISHED,
    NOT_FOUND,
    UNSUPPORTED,

    /** The job never reached the provider, so there was nothing to tell it. */
    NOTHING_TO_CANCEL,

    /** The configured engine changed since submission; its handle means nothing to the new one. */
    ENGINE_CHANGED,
    TIMED_OUT,
    FAILED,
}

/**
 * Tells the provider to stop a job the user cancelled, so a queued render doesn't run and bill.
 *
 * Only for a user's cancel. A worker that WorkManager stops (lost network, quota, OS pressure)
 * is resumed later from the saved handle, and cancelling remotely there would throw away a
 * render the user still wants. So this is driven by the CANCELLED state, never by coroutine
 * cancellation.
 */
@Singleton
class RemoteJobCanceller @Inject constructor(
    private val engines: EngineSource,
) {
    suspend fun cancel(job: CancelledJob): RemoteCancelOutcome {
        val remote = job.remote ?: return RemoteCancelOutcome.NOTHING_TO_CANCEL
        val engine = try {
            engines.open(EngineSlot.VIDEO_STUDIO)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: Exception) {
            return RemoteCancelOutcome.FAILED
        }
        return try {
            if (job.engineFingerprint != null && engine.config.fingerprint() != job.engineFingerprint) {
                RemoteCancelOutcome.ENGINE_CHANGED
            } else {
                requestRemoteCancel(engine, remote)
            }
        } finally {
            withContext(NonCancellable) { engine.close() }
        }
    }
}

/**
 * One bounded attempt at [GenerationEngine.cancel]. It runs in [NonCancellable] because the caller
 * is often being torn down at that moment (the worker stopped right after the user's cancel), and
 * the request must still go out; [REMOTE_CANCEL_TIMEOUT_MILLIS] keeps an offline device from
 * holding it up. Failures are reported, never thrown.
 */
internal suspend fun requestRemoteCancel(engine: GenerationEngine, remote: RemoteJob): RemoteCancelOutcome =
    withContext(NonCancellable) {
        try {
            when (withTimeoutOrNull(REMOTE_CANCEL_TIMEOUT_MILLIS) { engine.cancel(remote) }) {
                null -> RemoteCancelOutcome.TIMED_OUT
                RemoteCancelResult.REQUESTED -> RemoteCancelOutcome.REQUESTED
                RemoteCancelResult.ALREADY_FINISHED -> RemoteCancelOutcome.ALREADY_FINISHED
                RemoteCancelResult.NOT_FOUND -> RemoteCancelOutcome.NOT_FOUND
                RemoteCancelResult.UNSUPPORTED -> RemoteCancelOutcome.UNSUPPORTED
            }
        } catch (failure: Exception) {
            RemoteCancelOutcome.FAILED
        }
    }

/** Identifies the provider and model a handle belongs to. */
internal fun EngineConfig.fingerprint(): String =
    "$protocol|$restDialect|${endpointUrl.trimEnd('/')}|$modelId|${mcpToolName.orEmpty()}"

/** A cancel is one small request; a mobile link that can't manage it in this time is effectively offline. */
internal const val REMOTE_CANCEL_TIMEOUT_MILLIS: Long = 10_000
