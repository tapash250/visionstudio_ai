package com.visionstudio.feature.video.work

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.visionstudio.core.data.video.RemoteJobCanceller
import com.visionstudio.core.database.VisionStudioDatabase
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.core.model.RestDialect
import com.visionstudio.core.network.engine.BinaryPayload
import com.visionstudio.core.network.engine.RemoteJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoJobLauncherTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: VisionStudioDatabase
    private lateinit var jobs: DaoJobs
    private lateinit var launcher: WorkVideoJobLauncher
    private lateinit var workManager: WorkManager

    private val params = GenerationParameters(prompt = "a koi", modelId = "m", durationSeconds = 5)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, VisionStudioDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        jobs = DaoJobs(db.videoJobDao())
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        engine = StubEngine(config)
        launcher = WorkVideoJobLauncher(context, jobs, RemoteJobCanceller { engine }, appScope)
    }

    @After fun tearDown() {
        appScope.cancel()
        db.close()
    }

    private val config = EngineConfig(
        EngineSlot.VIDEO_STUDIO, ProviderProtocol.REST, "https://queue.fal.run", "fal-ai/kling", RestDialect.FAL_QUEUE,
    )
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var engine: StubEngine

    /** Puts [id] in the state a worker leaves it in once the provider has accepted it. */
    private suspend fun accepted(id: String, handle: RemoteJob, fingerprint: String = config.fingerprintForTest()) {
        db.videoJobDao().setRemoteJob(id, TestJson.encodeToString(RemoteJob.serializer(), handle), fingerprint, System.currentTimeMillis())
    }

    private fun EngineConfig.fingerprintForTest() = "$protocol|$restDialect|${endpointUrl.trimEnd('/')}|$modelId|${mcpToolName.orEmpty()}"

    private fun infoFor(jobId: String): WorkInfo =
        workManager.getWorkInfosForUniqueWork(WorkVideoJobLauncher.uniqueName(jobId)).get().single()

    @Test
    fun `launch saves the job and schedules one network-constrained worker carrying its id`() = runBlocking {
        val id = launcher.launch(params, null)

        assertNotNull(jobs.get(id))
        val info = infoFor(id)
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(WorkVideoJobLauncher.TAG in info.tags)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    @Test
    fun `two launches are two independent jobs`() = runBlocking {
        val first = launcher.launch(params, null)
        val second = launcher.launch(params.copy(prompt = "another"), null)

        assertFalse(first == second)
        assertEquals(WorkInfo.State.ENQUEUED, infoFor(first).state)
        assertEquals(WorkInfo.State.ENQUEUED, infoFor(second).state)
    }

    @Test
    fun `cancel records the cancellation and stops the scheduled work`() = runBlocking {
        val id = launcher.launch(params, null)

        launcher.cancel(id)

        assertEquals("CANCELLED", jobs.get(id)!!.status.name)
        assertEquals(WorkInfo.State.CANCELLED, infoFor(id).state)
    }

    @Test
    fun `cancelling an accepted job asks the provider to drop that exact job`() = runBlocking {
        val id = launcher.launch(params, null)
        val handle = RemoteJob("req-42", "https://queue.fal.run/fal-ai/kling/requests/req-42/status", cancelUrl = "https://queue.fal.run/fal-ai/kling/requests/req-42/cancel")
        accepted(id, handle)

        launcher.cancel(id)

        assertEquals("CANCELLED", jobs.get(id)!!.status.name)
        assertEquals(listOf(handle), engine.cancelled)
    }

    @Test
    fun `a job the provider never accepted is cancelled locally only`() = runBlocking {
        val id = launcher.launch(params, null)

        launcher.cancel(id)

        assertTrue("no provider call for a job that was only queued here", engine.cancelled.isEmpty())
    }

    @Test
    fun `a finished job is not cancelled anywhere`() = runBlocking {
        val id = launcher.launch(params, null)
        accepted(id, RemoteJob("req-1", "https://queue.fal.run/s"))
        jobs.fail(id, "boom")

        launcher.cancel(id)

        assertEquals("FAILED", jobs.get(id)!!.status.name)
        assertTrue(engine.cancelled.isEmpty())
    }

    @Test
    fun `a handle from a different engine is never sent to the current one`() = runBlocking {
        val id = launcher.launch(params, null)
        accepted(id, RemoteJob("other-1", "https://api.other.example/s"), fingerprint = "REST|CUSTOM|https://api.other.example|m|")

        launcher.cancel(id)

        assertEquals("CANCELLED", jobs.get(id)!!.status.name)
        assertTrue(engine.cancelled.isEmpty())
    }

    @Test
    fun `an unreachable provider never blocks or fails the local cancel`() = runBlocking {
        val id = launcher.launch(params, null)
        accepted(id, RemoteJob("req-9", "https://queue.fal.run/s"))
        engine.cancelResult = { throw IOException("offline") }

        launcher.cancel(id)

        assertEquals("CANCELLED", jobs.get(id)!!.status.name)
        assertEquals(WorkInfo.State.CANCELLED, infoFor(id).state)
        assertEquals(1, engine.cancelled.size)
    }

    @Test
    fun `retry re-queues a failed job and schedules fresh work`() = runBlocking {
        val id = launcher.launch(params, null)
        jobs.fail(id, "boom")
        workManager.cancelUniqueWork(WorkVideoJobLauncher.uniqueName(id)).result.get()

        assertTrue(launcher.retry(id))

        assertEquals("QUEUED", jobs.get(id)!!.status.name)
        assertEquals(WorkInfo.State.ENQUEUED, infoFor(id).state)
    }

    @Test
    fun `retry of a running job is refused and schedules nothing new`() = runBlocking {
        val id = launcher.launch(params, null)
        val before = workManager.getWorkInfosForUniqueWork(WorkVideoJobLauncher.uniqueName(id)).get().single().id

        assertFalse(launcher.retry(id))

        assertEquals(before, infoFor(id).id)
    }

    @Test
    fun `dismiss removes the record and the work`() = runBlocking {
        val id = launcher.launch(params, null)

        launcher.dismiss(id)

        assertEquals(null, jobs.get(id))
        assertEquals(WorkInfo.State.CANCELLED, infoFor(id).state)
    }

    @Test
    fun `a staged photo is carried with the job`() = runBlocking {
        val id = launcher.launch(params.copy(sourceImageUri = "content://x"), BinaryPayload(byteArrayOf(1, 2), "image/png"))

        assertTrue(jobs.get(id)!!.hasSourceImage)
    }
}
