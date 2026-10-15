package com.visionstudio.feature.video.work

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.visionstudio.core.data.video.VideoJob
import com.visionstudio.core.data.video.VideoJobRepository
import com.visionstudio.core.data.video.VideoJobStatus
import com.visionstudio.core.database.VideoJobDao
import com.visionstudio.core.database.VideoJobEntity
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.network.engine.BinaryPayload
import com.visionstudio.core.network.engine.ConnectionProbe
import com.visionstudio.core.network.engine.EngineOutput
import com.visionstudio.core.network.engine.EngineProgress
import com.visionstudio.core.network.engine.EngineRequest
import com.visionstudio.core.network.engine.GenerationEngine
import com.visionstudio.core.network.engine.JobStatus
import com.visionstudio.core.network.engine.RemoteJob
import com.visionstudio.core.network.engine.Submission
import com.visionstudio.feature.video.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.File

internal val TestJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** A [VideoJobRepository] over a real DAO, so tests exercise the real guarded SQL. */
internal class DaoJobs(private val dao: VideoJobDao, private val now: () -> Long = { System.currentTimeMillis() }) : VideoJobRepository {
    var lastCreated: String? = null

    override suspend fun create(parameters: GenerationParameters, sourceImage: BinaryPayload?): VideoJob {
        val id = "job-${lastCreated?.length ?: 0}-${System.nanoTime()}"
        val sourcePath = sourceImage?.let { File.createTempFile("src", ".png").also { f -> f.writeBytes(it.bytes) }.absolutePath }
        dao.upsert(
            VideoJobEntity(
                id = id, taskId = "task-$id", status = "QUEUED",
                parametersJson = TestJson.encodeToString(GenerationParameters.serializer(), parameters),
                sourcePath = sourcePath, remoteJobJson = null, engineFingerprint = null, progress = null, message = null,
                errorMessage = null, resultItemId = null, attempts = 0, createdAt = now(), updatedAt = now(),
            ),
        )
        lastCreated = id
        return get(id)!!
    }

    override fun observeJobs(limit: Int): Flow<List<VideoJob>> = dao.observeRecent(limit).map { rows -> rows.map { it.toJob() } }
    override suspend fun get(id: String): VideoJob? = dao.get(id)?.toJob()
    override suspend fun cancel(id: String) = dao.markCancelled(id, now()) > 0
    override suspend fun retry(id: String) = dao.resetForRetry(id, now()) > 0
    override suspend fun fail(id: String, message: String) { dao.markFailed(id, message, now()) }
    override suspend fun dismiss(id: String) { dao.delete(id) }

    private fun VideoJobEntity.toJob() = VideoJob(
        id = id, status = VideoJobStatus.of(status),
        parameters = TestJson.decodeFromString(GenerationParameters.serializer(), parametersJson),
        progress = progress, message = message, error = errorMessage, resultItemId = resultItemId,
        hasSourceImage = sourcePath != null, createdAtEpochMillis = createdAt, updatedAtEpochMillis = updatedAt,
    )
}

internal class RecordingNotifier(private val context: Context) : VideoNotifier {
    val foreground = mutableListOf<Triple<String, Float?, String?>>()
    val completed = mutableListOf<String>()
    val failed = mutableListOf<Pair<String, String>>()

    override fun foregroundInfo(jobId: String, prompt: String, progress: Float?, message: String?): ForegroundInfo {
        foreground += Triple(jobId, progress, message)
        val notification = NotificationCompat.Builder(context, "test")
            .setSmallIcon(R.drawable.ic_notification_video)
            .build()
        return ForegroundInfo(1, notification)
    }

    override fun showCompleted(jobId: String, prompt: String) { completed += jobId }
    override fun showFailed(jobId: String, prompt: String, reason: String) { failed += jobId to reason }
}

/** Provider stub: a resumed job asks [poll] straight away, so tests never wait out the 5 s delay. */
internal class StubEngine(override val config: EngineConfig) : GenerationEngine {
    val pollScript = ArrayDeque<() -> JobStatus>()
    var submitted = 0

    override suspend fun testConnection() = ConnectionProbe(true, "ok")
    override suspend fun submit(request: EngineRequest, onProgress: (EngineProgress) -> Unit): Submission {
        submitted++
        return Submission.Queued(RemoteJob("remote-1", "https://queue.fal.run/status"))
    }

    override suspend fun poll(job: RemoteJob): JobStatus = (pollScript.removeFirstOrNull() ?: { JobStatus.InProgress(0.1f) }).invoke()
    override suspend fun downloadTo(output: EngineOutput, target: File): Long = 0L
    override suspend fun close() = Unit
}
