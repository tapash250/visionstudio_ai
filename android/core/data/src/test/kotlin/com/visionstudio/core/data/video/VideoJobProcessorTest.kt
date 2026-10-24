package com.visionstudio.core.data.video

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.data.media.MediaSink
import com.visionstudio.core.database.VideoJobDao
import com.visionstudio.core.database.VisionStudioDatabase
import com.visionstudio.core.model.AspectRatio
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.core.model.RestDialect
import com.visionstudio.core.network.engine.BinaryPayload
import com.visionstudio.core.network.engine.ConnectionProbe
import com.visionstudio.core.network.engine.EngineOutput
import com.visionstudio.core.network.engine.EngineProgress
import com.visionstudio.core.network.engine.EngineRequest
import com.visionstudio.core.network.engine.GenerationEngine
import com.visionstudio.core.network.engine.JobStatus
import com.visionstudio.core.network.engine.RemoteCancelResult
import com.visionstudio.core.network.engine.RemoteJob
import com.visionstudio.core.network.engine.Submission
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoJobProcessorTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: VisionStudioDatabase
    private lateinit var dao: VideoJobDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, VisionStudioDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        dao = db.videoJobDao()
    }

    @After fun tearDown() = db.close()

    // region harness

    private val config = EngineConfig(
        slot = EngineSlot.VIDEO_STUDIO,
        protocol = ProviderProtocol.REST,
        endpointUrl = "https://queue.fal.run",
        modelId = "fal-ai/kling-video",
        restDialect = RestDialect.FAL_QUEUE,
    )

    private val params = GenerationParameters(
        prompt = "a koi in neon rain",
        aspectRatio = AspectRatio.LANDSCAPE_16_9,
        durationSeconds = 5,
        modelId = "fal-ai/kling-video",
    )

    private val videoOutput = EngineOutput.Remote("https://cdn.example.com/out.mp4", "video/mp4")

    private class ScriptedEngine(
        override val config: EngineConfig,
        private val now: () -> Long,
    ) : GenerationEngine {
        var submissions = 0
        var lastRequest: EngineRequest? = null
        var closed = 0
        val pollTimes = mutableListOf<Long>()
        var onSubmit: suspend () -> Submission = { Submission.Queued(RemoteJob("remote-1", "https://queue.fal.run/status")) }
        val pollScript = ArrayDeque<suspend () -> JobStatus>()
        var pollFallback: suspend () -> JobStatus = { JobStatus.InProgress(0.1f) }

        override suspend fun testConnection() = ConnectionProbe(true, "ok")

        override suspend fun submit(request: EngineRequest, onProgress: (EngineProgress) -> Unit): Submission {
            submissions++
            lastRequest = request
            return onSubmit()
        }

        override suspend fun poll(job: RemoteJob): JobStatus {
            pollTimes += now()
            return (pollScript.removeFirstOrNull() ?: pollFallback).invoke()
        }

        val cancelled = mutableListOf<RemoteJob>()
        override suspend fun cancel(job: RemoteJob): RemoteCancelResult {
            cancelled += job
            return RemoteCancelResult.REQUESTED
        }

        override suspend fun downloadTo(output: EngineOutput, target: File): Long = 0L
        override suspend fun close() {
            closed++
        }
    }

    private class RecordingSink : MediaSink {
        val stored = mutableListOf<EngineOutput>()
        var failure: Exception? = null

        override suspend fun store(
            engine: GenerationEngine,
            output: EngineOutput,
            slot: EngineSlot,
            taskId: String,
            parameters: GenerationParameters,
            createdAt: Long,
        ): MediaItem {
            failure?.let { throw it }
            stored += output
            return MediaItem(
                id = "item-${stored.size}", taskId = taskId, type = MediaType.VIDEO, localUri = "file:///v.mp4",
                width = 1280, height = 720, durationMillis = 5_000, sizeBytes = 1, parameters = parameters,
                createdAtEpochMillis = createdAt,
            )
        }
    }

    private inner class Rig(scope: TestScope) {
        val clock = Clock { scope.testScheduler.currentTime }
        val engine = ScriptedEngine(config) { scope.testScheduler.currentTime }
        val sink = RecordingSink()
        var engineFactory: suspend () -> GenerationEngine = { engine }
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val repository = DefaultVideoJobRepository(dao, context, dispatcher, dispatcher, clock)
        val processor = VideoJobProcessor(dao, { engineFactory() }, sink, clock, dispatcher)

        suspend fun newJob(source: BinaryPayload? = null): String =
            repository.create(if (source != null) params.copy(sourceImageUri = "content://picked") else params, source).id
    }

    private fun completedWith(vararg outputs: EngineOutput) = JobStatus.Completed(outputs.toList())

    // endregion

    @Test
    fun `submits once, stores the handle before polling, and polls every 5 to 10 seconds`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        var handleStoredBeforeFirstPoll = false
        rig.engine.pollScript += {
            val row = dao.get(id)!!
            handleStoredBeforeFirstPoll = row.remoteJobJson != null && row.status == "POLLING"
            JobStatus.InProgress(0.2f, message = "Rendering")
        }
        repeat(5) { rig.engine.pollScript += { JobStatus.InProgress(0.5f) } }
        rig.engine.pollScript += { completedWith(videoOutput) }

        val outcome = rig.processor.run(id)

        assertTrue("outcome was $outcome", outcome is VideoJobOutcome.Completed)
        assertEquals(1, rig.engine.submissions)
        assertTrue(handleStoredBeforeFirstPoll)

        val times = rig.engine.pollTimes
        val gaps = times.zipWithNext { a, b -> b - a }
        assertEquals("first poll waits the opening 5s", 5_000L, times.first())
        assertTrue("every gap is 5-10s: $gaps", gaps.all { it in 5_000L..10_000L })
        assertEquals("gaps never shrink: $gaps", gaps.sorted(), gaps)
        assertEquals("backoff reaches the 10s cap", 10_000L, gaps.last())
        assertTrue("backoff actually grows: $gaps", gaps.first() < gaps.last())

        val row = dao.get(id)!!
        assertEquals("COMPLETED", row.status)
        assertEquals("item-1", row.resultItemId)
        assertEquals(1, rig.sink.stored.size)
        assertEquals(1, rig.engine.closed)
    }

    @Test
    fun `a restarted run polls the stored handle and never submits again`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        dao.setFingerprint(id, "REST|FAL_QUEUE|https://queue.fal.run|fal-ai/kling-video|", 0)
        dao.setRemoteJob(
            id,
            """{"id":"remote-1","statusUrl":"https://queue.fal.run/status"}""",
            "REST|FAL_QUEUE|https://queue.fal.run|fal-ai/kling-video|",
            0,
        )
        rig.engine.pollScript += { completedWith(videoOutput) }

        val outcome = rig.processor.run(id)

        assertTrue(outcome is VideoJobOutcome.Completed)
        assertEquals("no second paid submission", 0, rig.engine.submissions)
        assertEquals("a resumed job is asked immediately", listOf(0L), rig.engine.pollTimes)
    }

    @Test
    fun `a synchronous provider result needs no polling`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        rig.engine.onSubmit = { Submission.Completed(listOf(videoOutput)) }

        val outcome = rig.processor.run(id)

        assertTrue(outcome is VideoJobOutcome.Completed)
        assertEquals(0, rig.engine.pollTimes.size)
        assertEquals("COMPLETED", dao.statusOf(id))
    }

    @Test
    fun `image-to-video sends the staged photo and then deletes it`() = runTest {
        val rig = Rig(this)
        val photo = BinaryPayload(byteArrayOf(1, 2, 3), "image/png")
        val id = rig.newJob(photo)
        val staged = File(dao.get(id)!!.sourcePath!!)
        assertTrue(staged.isFile)
        rig.engine.onSubmit = { Submission.Completed(listOf(videoOutput)) }

        rig.processor.run(id)

        assertEquals(listOf<Byte>(1, 2, 3), rig.engine.lastRequest!!.sourceImage!!.bytes.toList())
        assertEquals("image/png", rig.engine.lastRequest!!.sourceImage!!.mimeType)
        assertFalse("source image is cleaned up after success", staged.exists())
    }

    @Test
    fun `a missing staged photo fails the job instead of submitting a text-only video`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob(BinaryPayload(byteArrayOf(9), "image/png"))
        File(dao.get(id)!!.sourcePath!!).delete()

        val outcome = rig.processor.run(id)

        assertTrue(outcome is VideoJobOutcome.Failed)
        assertEquals(0, rig.engine.submissions)
        assertEquals("FAILED", dao.statusOf(id))
    }

    @Test
    fun `provider reported failure is recorded with its reason`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        rig.engine.pollScript += { JobStatus.InProgress(0.3f) }
        rig.engine.pollScript += { JobStatus.Failed("Prompt rejected by safety filter") }

        val outcome = rig.processor.run(id)

        assertEquals(VideoJobOutcome.Failed("Prompt rejected by safety filter"), outcome)
        val row = dao.get(id)!!
        assertEquals("FAILED", row.status)
        assertEquals("Prompt rejected by safety filter", row.errorMessage)
    }

    @Test
    fun `a transient submit error retries later and submits then, not before`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        var first = true
        rig.engine.onSubmit = {
            if (first) {
                first = false
                throw VisionStudioException.NetworkException("offline")
            }
            Submission.Completed(listOf(videoOutput))
        }

        val firstRun = rig.processor.run(id)
        assertTrue(firstRun is VideoJobOutcome.Retry)
        assertTrue("still alive for the next run", VideoJobStatus.of(dao.statusOf(id)!!).isActive)
        assertNull("no handle was ever issued", dao.get(id)!!.remoteJobJson)

        val secondRun = rig.processor.run(id)
        assertTrue(secondRun is VideoJobOutcome.Completed)
        assertEquals(2, rig.engine.submissions)
        assertEquals(2, dao.get(id)!!.attempts)
    }

    @Test
    fun `a permanent submit error fails the job`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        rig.engine.onSubmit = { throw VisionStudioException.AuthenticationException(EngineSlot.VIDEO_STUDIO, 401, "bad key") }

        val outcome = rig.processor.run(id)

        assertEquals(VideoJobOutcome.Failed("bad key"), outcome)
        assertEquals("FAILED", dao.statusOf(id))
    }

    @Test
    fun `an overloaded provider is retried but a bad request is not`() = runTest {
        val rig = Rig(this)
        val busy = rig.newJob()
        rig.engine.onSubmit = { throw VisionStudioException.ProviderException(503, "busy") }
        assertTrue(rig.processor.run(busy) is VideoJobOutcome.Retry)

        val rateLimited = rig.newJob()
        rig.engine.onSubmit = { throw VisionStudioException.ProviderException(429, "slow down") }
        assertTrue(rig.processor.run(rateLimited) is VideoJobOutcome.Retry)

        val invalid = rig.newJob()
        rig.engine.onSubmit = { throw VisionStudioException.ProviderException(422, "bad aspect ratio") }
        assertTrue(rig.processor.run(invalid) is VideoJobOutcome.Failed)
    }

    @Test
    fun `a few poll errors are tolerated`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        repeat(4) { rig.engine.pollScript += { throw VisionStudioException.NetworkException("blip") } }
        rig.engine.pollScript += { completedWith(videoOutput) }

        val outcome = rig.processor.run(id)

        assertTrue("outcome was $outcome", outcome is VideoJobOutcome.Completed)
        assertEquals(1, rig.engine.submissions)
    }

    @Test
    fun `a sustained outage hands back Retry with the handle intact`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        rig.engine.pollFallback = { throw VisionStudioException.NetworkException("down") }

        val outcome = rig.processor.run(id)

        assertTrue(outcome is VideoJobOutcome.Retry)
        assertEquals(VideoJobProcessor.MAX_CONSECUTIVE_POLL_ERRORS, rig.engine.pollTimes.size)
        val row = dao.get(id)!!
        assertEquals("POLLING", row.status)
        assertNotNull("the handle survives so the next run resumes", row.remoteJobJson)
    }

    @Test
    fun `an authentication error while polling fails immediately`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        rig.engine.pollScript += { throw VisionStudioException.AuthenticationException(EngineSlot.VIDEO_STUDIO, 403, "revoked") }

        val outcome = rig.processor.run(id)

        assertEquals(VideoJobOutcome.Failed("revoked"), outcome)
        assertEquals(1, rig.engine.pollTimes.size)
    }

    @Test
    fun `cancelling stops polling at the next check and stays cancelled`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        rig.engine.pollScript += {
            dao.markCancelled(id, 0) // the user taps Cancel while this poll is in flight
            JobStatus.InProgress(0.4f)
        }

        val outcome = rig.processor.run(id)

        assertEquals(VideoJobOutcome.Cancelled, outcome)
        assertEquals(1, rig.engine.pollTimes.size)
        assertEquals("CANCELLED", dao.statusOf(id))
        assertTrue("a stored handle is cancelled by the user's cancel path, not here", rig.engine.cancelled.isEmpty())
    }

    @Test
    fun `a cancelled job that was never started does nothing`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        rig.repository.cancel(id)

        assertEquals(VideoJobOutcome.Cancelled, rig.processor.run(id))
        assertEquals(0, rig.engine.submissions)
    }

    @Test
    fun `a handle from a different engine is never polled`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        dao.setRemoteJob(id, """{"id":"theirs","statusUrl":"https://other.example/status"}""", "REST|OPENAI_COMPATIBLE|https://other.example|sora|", 0)

        val outcome = rig.processor.run(id)

        assertTrue(outcome is VideoJobOutcome.Failed)
        assertTrue((outcome as VideoJobOutcome.Failed).message.contains("engine was changed"))
        assertEquals(0, rig.engine.pollTimes.size)
        assertEquals(0, rig.engine.submissions)
    }

    @Test
    fun `an unconfigured engine fails the job with a clear message`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        rig.engineFactory = { throw VisionStudioException.ValidationException(null, "Video Studio engine is not configured") }

        val outcome = rig.processor.run(id)

        assertEquals(VideoJobOutcome.Failed("Video Studio engine is not configured"), outcome)
    }

    @Test
    fun `a job the provider never finishes eventually fails`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        rig.engine.pollFallback = { JobStatus.InProgress(0.9f) }

        val outcome = rig.processor.run(id)

        assertTrue(outcome is VideoJobOutcome.Failed)
        assertTrue((outcome as VideoJobOutcome.Failed).message.contains("45 minutes"))
        assertTrue("polled for roughly the whole budget", rig.engine.pollTimes.last() >= VideoJobProcessor.MAX_JOB_MILLIS)
    }

    @Test
    fun `a download failure keeps the job resumable without resubmitting`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        rig.engine.pollScript += { completedWith(videoOutput) }
        rig.engine.pollScript += { completedWith(videoOutput) }
        rig.sink.failure = VisionStudioException.NetworkException("connection reset")

        val first = rig.processor.run(id)
        assertTrue(first is VideoJobOutcome.Retry)
        assertEquals("DOWNLOADING", dao.statusOf(id))

        rig.sink.failure = null
        val second = rig.processor.run(id)
        assertTrue(second is VideoJobOutcome.Completed)
        assertEquals("one paid submission across both runs", 1, rig.engine.submissions)
    }

    @Test
    fun `finished jobs are not run again`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        rig.engine.onSubmit = { Submission.Completed(listOf(videoOutput)) }
        rig.processor.run(id)

        assertEquals(VideoJobOutcome.AlreadyFinished, rig.processor.run(id))
        assertEquals(1, rig.engine.submissions)
    }

    @Test
    fun `being stopped by the system leaves the job resumable`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        rig.engine.pollFallback = { JobStatus.InProgress(0.5f) }

        val running = launch { rig.processor.run(id) }
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, rig.engine.pollTimes.size)

        running.cancelAndJoin() // WorkManager stops the worker (10-minute cap, process death, constraints lost)

        val row = dao.get(id)!!
        assertEquals("not failed, not cancelled", "POLLING", row.status)
        assertNotNull(row.remoteJobJson)
        assertEquals("the engine is still closed on the way out", 1, rig.engine.closed)
        assertTrue("a system stop must never cancel the render at the provider", rig.engine.cancelled.isEmpty())
    }

    @Test
    fun `a missing job fails without touching the provider`() = runTest {
        val rig = Rig(this)

        val outcome = rig.processor.run("nope")

        assertTrue(outcome is VideoJobOutcome.Failed)
        assertEquals(0, rig.engine.submissions)
    }

    // region flaws found in review

    @Test
    fun `a submit that timed out may already be running at the provider, so it is not blindly resubmitted`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        rig.engine.onSubmit = { throw VisionStudioException.TimeoutException("read timed out") }

        val outcome = rig.processor.run(id)

        // A timeout after the request left the device is ambiguous: retrying could create (and bill) a second job.
        assertTrue("was $outcome", outcome is VideoJobOutcome.Failed)
        assertTrue((outcome as VideoJobOutcome.Failed).message.contains("may have started"))
        assertEquals("FAILED", dao.statusOf(id))
        assertEquals(1, rig.engine.submissions)
    }

    @Test
    fun `a connection that never reached the provider is still safe to retry`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        rig.engine.onSubmit = { throw VisionStudioException.NetworkException("Unable to resolve host") }

        assertTrue(rig.processor.run(id) is VideoJobOutcome.Retry)
        assertTrue(VideoJobStatus.of(dao.statusOf(id)!!).isActive)
    }

    @Test
    fun `cancelling while the provider is accepting the job ends cancelled, not orphaned as polling`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        rig.engine.onSubmit = {
            dao.markCancelled(id, 0) // user taps Cancel while the submit call is in flight
            Submission.Queued(RemoteJob("remote-1", "https://queue.fal.run/status"))
        }

        val outcome = rig.processor.run(id)

        assertEquals(VideoJobOutcome.Cancelled, outcome)
        assertEquals("never polled a job the user cancelled", 0, rig.engine.pollTimes.size)
        assertEquals("CANCELLED", dao.statusOf(id))
        assertEquals("only this run knows the new handle, so it tells the provider", listOf("remote-1"), rig.engine.cancelled.map { it.id })
    }

    @Test
    fun `cancel then retry mid-poll makes the old run stand down instead of racing the new one`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        rig.engine.pollScript += {
            // While this poll is in flight the user cancels and immediately retries the job.
            dao.markCancelled(id, 0)
            dao.resetForRetry(id, 0)
            JobStatus.InProgress(0.5f)
        }
        rig.engine.pollScript += { completedWith(videoOutput) } // would wrongly complete the *old* run

        val outcome = rig.processor.run(id)

        assertEquals(VideoJobOutcome.Cancelled, outcome)
        assertEquals("the old run polled once and then stood down", 1, rig.engine.pollTimes.size)
        assertEquals("the retried job is left for its own fresh run", "QUEUED", dao.statusOf(id))
        assertTrue("and nothing was stored for it", rig.sink.stored.isEmpty())
    }

    @Test
    fun `a corrupt stored handle fails the job instead of silently submitting again`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        dao.setRemoteJob(id, "{this is not json", "REST|FAL_QUEUE|https://queue.fal.run|fal-ai/kling-video|", 0)

        val outcome = rig.processor.run(id)

        assertTrue("was $outcome", outcome is VideoJobOutcome.Failed)
        assertEquals("a paid submission must not be repeated on a guess", 0, rig.engine.submissions)
    }

    // endregion

    // region repository

    @Test
    fun `retry re-queues a failed job and drops its old handle`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob()
        dao.setRemoteJob(id, """{"id":"x","statusUrl":"u"}""", "fp", 0)
        dao.markFailed(id, "boom", 0)

        assertTrue(rig.repository.retry(id))

        val row = dao.get(id)!!
        assertEquals("QUEUED", row.status)
        assertNull(row.remoteJobJson)
    }

    @Test
    fun `retry refuses when the staged photo is gone and when the job is still running`() = runTest {
        val rig = Rig(this)
        val withPhoto = rig.newJob(BinaryPayload(byteArrayOf(1), "image/png"))
        dao.markFailed(withPhoto, "boom", 0)
        File(dao.get(withPhoto)!!.sourcePath!!).delete()
        assertFalse(rig.repository.retry(withPhoto))

        val running = rig.newJob()
        assertFalse(rig.repository.retry(running))
    }

    @Test
    fun `dismiss removes the record and its staged photo`() = runTest {
        val rig = Rig(this)
        val id = rig.newJob(BinaryPayload(byteArrayOf(1), "image/png"))
        val staged = File(dao.get(id)!!.sourcePath!!)

        rig.repository.dismiss(id)

        assertNull(dao.get(id))
        assertFalse(staged.exists())
    }

    @Test
    fun `image-to-video without bytes is rejected up front`() = runTest {
        val rig = Rig(this)
        val error = runCatching { rig.repository.create(params.copy(sourceImageUri = "content://x"), null) }.exceptionOrNull()
        assertTrue(error is VisionStudioException.ValidationException)
    }

    // endregion
}
