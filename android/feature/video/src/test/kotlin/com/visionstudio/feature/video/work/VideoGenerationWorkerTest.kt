package com.visionstudio.feature.video.work

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.data.media.MediaSink
import com.visionstudio.core.data.video.Clock
import com.visionstudio.core.data.video.VideoJobProcessor
import com.visionstudio.core.database.VideoJobDao
import com.visionstudio.core.database.VisionStudioDatabase
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.core.model.RestDialect
import com.visionstudio.core.network.engine.EngineOutput
import com.visionstudio.core.network.engine.GenerationEngine
import com.visionstudio.core.network.engine.JobStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoGenerationWorkerTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: VisionStudioDatabase
    private lateinit var dao: VideoJobDao
    private lateinit var jobs: DaoJobs
    private lateinit var notifier: RecordingNotifier
    private lateinit var engine: StubEngine

    private val config = EngineConfig(
        EngineSlot.VIDEO_STUDIO, ProviderProtocol.REST, "https://queue.fal.run", "fal-ai/kling", RestDialect.FAL_QUEUE,
    )
    private val fingerprint = "REST|FAL_QUEUE|https://queue.fal.run|fal-ai/kling|"
    private val params = GenerationParameters(prompt = "a koi in neon rain", modelId = "fal-ai/kling", durationSeconds = 5)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, VisionStudioDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        dao = db.videoJobDao()
        jobs = DaoJobs(dao)
        notifier = RecordingNotifier(context)
        engine = StubEngine(config)
    }

    @After fun tearDown() = db.close()

    private val sink = object : MediaSink {
        override suspend fun store(
            engine: GenerationEngine, output: EngineOutput, slot: EngineSlot, taskId: String,
            parameters: GenerationParameters, createdAt: Long,
        ) = MediaItem(
            id = "stored-item", taskId = taskId, type = MediaType.VIDEO, localUri = "file:///v.mp4", width = 1280,
            height = 720, durationMillis = 5_000, sizeBytes = 1, parameters = parameters, createdAtEpochMillis = createdAt,
        )
    }

    /** A job that already holds a provider handle, so the processor resumes and polls immediately. */
    private suspend fun resumableJob(): String {
        val id = jobs.create(params, null).id
        dao.setRemoteJob(id, """{"id":"remote-1","statusUrl":"https://queue.fal.run/status"}""", fingerprint, 0)
        return id
    }

    private fun worker(jobId: String?, attempt: Int = 0) = TestListenableWorkerBuilder<VideoGenerationWorker>(context)
        .setInputData(if (jobId == null) workDataOf() else workDataOf(VideoGenerationWorker.KEY_JOB_ID to jobId))
        .setRunAttemptCount(attempt)
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(appContext: android.content.Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                    val processor = VideoJobProcessor(dao, { engine }, sink, Clock { System.currentTimeMillis() }, Dispatchers.IO)
                    return VideoGenerationWorker(appContext, workerParameters, processor, jobs, notifier)
                }
            },
        )
        .build()

    @Test
    fun `a finished render succeeds, notifies once and reports the saved item`() = runBlocking {
        val id = resumableJob()
        engine.pollScript += { JobStatus.Completed(listOf(EngineOutput.Remote("https://cdn.example.com/v.mp4", "video/mp4"))) }

        val result = worker(id).doWork()

        assertTrue("was $result", result is ListenableWorker.Result.Success)
        assertEquals("stored-item", (result as ListenableWorker.Result.Success).outputData.getString(VideoGenerationWorker.KEY_ITEM_ID))
        assertEquals(listOf(id), notifier.completed)
        assertTrue(notifier.failed.isEmpty())
        assertEquals("COMPLETED", dao.statusOf(id))
        assertTrue("promoted to foreground at start", notifier.foreground.isNotEmpty())
        assertEquals("a resumed job never submits", 0, engine.submitted)
    }

    @Test
    fun `a provider failure fails the work and tells the user why`() = runBlocking {
        val id = resumableJob()
        engine.pollScript += { JobStatus.Failed("Prompt rejected by safety filter") }

        val result = worker(id).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(listOf(id to "Prompt rejected by safety filter"), notifier.failed)
        assertTrue(notifier.completed.isEmpty())
        assertEquals("FAILED", dao.statusOf(id))
    }

    @Test
    fun `a transient outage asks WorkManager to retry without alerting the user`() = runTest {
        val id = resumableJob()
        engine.pollScript.addAll(List(VideoJobProcessor.MAX_CONSECUTIVE_POLL_ERRORS + 1) { { throw VisionStudioException.NetworkException("offline") } })

        val result = worker(id, attempt = 0).doWork()

        assertTrue("was $result", result is ListenableWorker.Result.Retry)
        assertTrue(notifier.failed.isEmpty())
        assertTrue(notifier.completed.isEmpty())
        assertEquals("job stays alive and resumable", "POLLING", dao.statusOf(id))
    }

    @Test
    fun `retries are bounded - the last attempt fails the job and notifies`() = runTest {
        val id = resumableJob()
        engine.pollScript.addAll(List(VideoJobProcessor.MAX_CONSECUTIVE_POLL_ERRORS + 1) { { throw VisionStudioException.NetworkException("offline") } })

        val result = worker(id, attempt = VideoGenerationWorker.MAX_ATTEMPTS - 1).doWork()

        assertTrue("was $result", result is ListenableWorker.Result.Failure)
        assertEquals("FAILED", dao.statusOf(id))
        assertEquals(1, notifier.failed.size)
        assertTrue(notifier.failed.single().second.contains("${VideoGenerationWorker.MAX_ATTEMPTS} tries"))
    }

    @Test
    fun `a cancelled job ends quietly`() = runBlocking {
        val id = resumableJob()
        jobs.cancel(id)

        val result = worker(id).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue("no alert for something the user did", notifier.failed.isEmpty() && notifier.completed.isEmpty())
        assertEquals("CANCELLED", dao.statusOf(id))
    }

    @Test
    fun `an already finished job is a no-op success`() = runBlocking {
        val id = resumableJob()
        dao.markCompleted(id, "earlier", 0)

        val result = worker(id).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(notifier.completed.isEmpty())
    }

    @Test
    fun `missing or unknown job ids fail without side effects`() = runBlocking {
        assertTrue(worker(null).doWork() is ListenableWorker.Result.Failure)
        assertTrue(worker("does-not-exist").doWork() is ListenableWorker.Result.Failure)
        assertTrue(notifier.failed.isEmpty() && notifier.completed.isEmpty())
        assertEquals(0, engine.submitted)
    }
}
