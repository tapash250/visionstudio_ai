package com.visionstudio.feature.video.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.visionstudio.core.data.video.VideoJobOutcome
import com.visionstudio.core.data.video.VideoJobProcessor
import com.visionstudio.core.data.video.VideoJobRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * Runs one video job in the background.
 *
 * All the logic that must be correct across restarts lives in [VideoJobProcessor]: the provider
 * handle is persisted, so if WorkManager stops this worker (the 10-minute execution cap,
 * process death, lost network) and starts it again, it resumes polling the same provider job.
 * This class only adapts the processor to WorkManager and the notification shade:
 *
 * - promotes itself to a data-sync foreground service so a long render is not killed, and
 *   keeps the ongoing notification's progress current;
 * - maps outcomes to [Result] (success / failure / retry with WorkManager's exponential backoff);
 * - gives up after [MAX_ATTEMPTS] consecutive transient failures so an unreachable provider
 *   cannot retry forever;
 * - posts a notification when the video is ready or the render failed.
 */
@HiltWorker
class VideoGenerationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: VideoJobProcessor,
    private val jobs: VideoJobRepository,
    private val notifier: VideoNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val job = jobs.get(jobId) ?: return Result.failure()
        val prompt = job.parameters.prompt

        promoteToForeground(jobId, prompt, progress = null, message = null)
        var lastPercent = -1
        val outcome = try {
            processor.run(jobId) { progress, message ->
                val percent = progress?.let { (it * PERCENT).roundToInt() } ?: -1
                if (percent != lastPercent || progress == null) {
                    lastPercent = percent
                    // Fire and forget: the notification is a courtesy and must never stall polling.
                    setForegroundAsync(notifier.foregroundInfo(jobId, prompt, progress, message))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unexpected: Exception) {
            val reason = unexpected.message ?: "Unexpected error"
            jobs.fail(jobId, reason)
            notifier.showFailed(jobId, prompt, reason)
            return Result.failure()
        }

        return when (outcome) {
            is VideoJobOutcome.Completed -> {
                notifier.showCompleted(jobId, prompt)
                Result.success(workDataOf(KEY_ITEM_ID to outcome.item.id))
            }
            is VideoJobOutcome.Failed -> {
                notifier.showFailed(jobId, prompt, outcome.message)
                Result.failure()
            }
            is VideoJobOutcome.Retry -> if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                val reason = "Couldn't reach the provider after $MAX_ATTEMPTS tries: ${outcome.reason}"
                jobs.fail(jobId, reason)
                notifier.showFailed(jobId, prompt, reason)
                Result.failure()
            } else {
                Result.retry()
            }
            VideoJobOutcome.Cancelled -> Result.failure()
            VideoJobOutcome.AlreadyFinished -> Result.success()
        }
    }

    /** Used by WorkManager when the system needs the foreground notification before doWork runs. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val jobId = inputData.getString(KEY_JOB_ID).orEmpty()
        val prompt = jobs.get(jobId)?.parameters?.prompt.orEmpty()
        return notifier.foregroundInfo(jobId, prompt, progress = null, message = null)
    }

    /**
     * On Android 12+ a background app may not start a foreground service. If promotion is
     * refused the worker simply carries on as ordinary background work: if the system later
     * stops it, WorkManager restarts it and the processor resumes from the saved handle.
     */
    private suspend fun promoteToForeground(jobId: String, prompt: String, progress: Float?, message: String?) {
        try {
            setForeground(notifier.foregroundInfo(jobId, prompt, progress, message))
        } catch (refused: IllegalStateException) {
            // Foreground start not allowed right now; continue in the background.
        }
    }

    companion object {
        const val KEY_JOB_ID: String = "job_id"
        const val KEY_ITEM_ID: String = "item_id"
        const val MAX_ATTEMPTS: Int = 5
        private const val PERCENT = 100f
    }
}
