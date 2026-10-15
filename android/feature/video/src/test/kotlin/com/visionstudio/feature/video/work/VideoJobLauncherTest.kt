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
import com.visionstudio.core.database.VisionStudioDatabase
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.network.engine.BinaryPayload
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
        launcher = WorkVideoJobLauncher(context, jobs)
    }

    @After fun tearDown() = db.close()

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
