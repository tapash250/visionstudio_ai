package com.visionstudio.feature.video.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.visionstudio.core.common.dispatchers.ApplicationScope
import com.visionstudio.core.data.video.RemoteJobCanceller
import com.visionstudio.core.data.video.VideoJobRepository
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.network.engine.BinaryPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Creates, cancels and re-runs video jobs. The only place that talks to WorkManager. */
interface VideoJobLauncher {
    /** Saves the job and schedules its worker. Returns the job id. */
    suspend fun launch(parameters: GenerationParameters, sourceImage: BinaryPayload?): String

    /**
     * Stops the job and, where the provider supports it, asks the provider to drop it too. A
     * queued render is then never run; one already rendering may still finish (and bill).
     */
    suspend fun cancel(jobId: String)

    suspend fun retry(jobId: String): Boolean

    suspend fun dismiss(jobId: String)
}

@Singleton
internal class WorkVideoJobLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jobs: VideoJobRepository,
    private val remote: RemoteJobCanceller,
    @ApplicationScope private val appScope: CoroutineScope,
) : VideoJobLauncher {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override suspend fun launch(parameters: GenerationParameters, sourceImage: BinaryPayload?): String {
        val job = jobs.create(parameters, sourceImage)
        enqueue(job.id, ExistingWorkPolicy.KEEP)
        return job.id
    }

    override suspend fun cancel(jobId: String) {
        // Record the cancellation first: a worker that is mid-poll reads it on its next check,
        // and one that is restarted later sees a terminal job and does nothing.
        val cancelled = jobs.cancel(jobId)
        workManager.cancelUniqueWork(uniqueName(jobId))
        // Then tell the provider, on the app's scope so leaving the screen doesn't abandon the
        // request. Best effort and bounded; the job is already cancelled here whatever it says.
        if (cancelled?.remote != null) appScope.launch { remote.cancel(cancelled) }
    }

    override suspend fun retry(jobId: String): Boolean {
        if (!jobs.retry(jobId)) return false
        enqueue(jobId, ExistingWorkPolicy.REPLACE)
        return true
    }

    override suspend fun dismiss(jobId: String) {
        workManager.cancelUniqueWork(uniqueName(jobId))
        jobs.dismiss(jobId)
    }

    private fun enqueue(jobId: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<VideoGenerationWorker>()
            .setInputData(workDataOf(VideoGenerationWorker.KEY_JOB_ID to jobId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(uniqueName(jobId), policy, request)
    }

    companion object {
        const val TAG: String = "video-job"
        private const val BACKOFF_SECONDS = 30L
        internal fun uniqueName(jobId: String) = "video-job-$jobId"
    }
}
