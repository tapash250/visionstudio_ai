package com.visionstudio.core.data.video

import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.core.model.RestDialect
import com.visionstudio.core.network.engine.ConnectionProbe
import com.visionstudio.core.network.engine.EngineOutput
import com.visionstudio.core.network.engine.EngineProgress
import com.visionstudio.core.network.engine.EngineRequest
import com.visionstudio.core.network.engine.GenerationEngine
import com.visionstudio.core.network.engine.JobStatus
import com.visionstudio.core.network.engine.RemoteCancelResult
import com.visionstudio.core.network.engine.RemoteJob
import com.visionstudio.core.network.engine.Submission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteJobCancellerTest {

    private val config = EngineConfig(
        EngineSlot.VIDEO_STUDIO, ProviderProtocol.REST, "https://queue.fal.run", "fal-ai/kling", RestDialect.FAL_QUEUE,
    )
    private val handle = RemoteJob("req-1", "https://queue.fal.run/s", cancelUrl = "https://queue.fal.run/c")

    private class CancelEngine(override val config: EngineConfig, private val answer: suspend () -> RemoteCancelResult) : GenerationEngine {
        val cancelled = mutableListOf<RemoteJob>()
        var closed = 0
        override suspend fun testConnection() = ConnectionProbe(true, "ok")
        override suspend fun submit(request: EngineRequest, onProgress: (EngineProgress) -> Unit): Submission = error("not used")
        override suspend fun poll(job: RemoteJob): JobStatus = error("not used")
        override suspend fun cancel(job: RemoteJob): RemoteCancelResult {
            cancelled += job
            return answer()
        }
        override suspend fun downloadTo(output: EngineOutput, target: File): Long = 0L
        override suspend fun close() {
            closed++
        }
    }

    @Test
    fun `asks the matching engine to cancel the stored handle and closes it`() = runTest {
        val engine = CancelEngine(config) { RemoteCancelResult.REQUESTED }

        val outcome = RemoteJobCanceller { engine }.cancel(CancelledJob("j", handle, config.fingerprint()))

        assertEquals(RemoteCancelOutcome.REQUESTED, outcome)
        assertEquals(listOf(handle), engine.cancelled)
        assertEquals(1, engine.closed)
    }

    @Test
    fun `nothing is sent when there is no handle or the engine changed`() = runTest {
        val engine = CancelEngine(config) { RemoteCancelResult.REQUESTED }
        val canceller = RemoteJobCanceller { engine }

        assertEquals(RemoteCancelOutcome.NOTHING_TO_CANCEL, canceller.cancel(CancelledJob("j", null, null)))
        assertEquals(RemoteCancelOutcome.ENGINE_CHANGED, canceller.cancel(CancelledJob("j", handle, "REST|CUSTOM|https://x|m|")))
        assertTrue(engine.cancelled.isEmpty())
    }

    @Test
    fun `provider errors and an unavailable engine are reported, never thrown`() = runTest {
        val failing = CancelEngine(config) { throw IOException("offline") }

        assertEquals(RemoteCancelOutcome.FAILED, RemoteJobCanceller { failing }.cancel(CancelledJob("j", handle, null)))
        assertEquals(RemoteCancelOutcome.FAILED, RemoteJobCanceller { throw IOException("no engine") }.cancel(CancelledJob("j", handle, null)))
        assertEquals("closed even after a failure", 1, failing.closed)
    }

    @Test
    fun `a provider that never answers is given up on after the timeout`() = runTest {
        val hanging = CancelEngine(config) { awaitCancellation() }

        val outcome = RemoteJobCanceller { hanging }.cancel(CancelledJob("j", handle, null))

        assertEquals(RemoteCancelOutcome.TIMED_OUT, outcome)
        assertTrue("virtual time advanced exactly to the bound", testScheduler.currentTime == REMOTE_CANCEL_TIMEOUT_MILLIS)
    }

    @Test
    fun `the request still goes out when the caller is cancelled mid-flight`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<RemoteCancelResult>()
        val engine = CancelEngine(config) {
            entered.complete(Unit)
            release.await()
        }
        var outcome: RemoteCancelOutcome? = null

        val caller = launch { outcome = requestRemoteCancel(engine, handle) }
        runCurrent()
        entered.await()
        caller.cancel() // the worker is torn down right after the user's cancel
        advanceTimeBy(1_000)
        release.complete(RemoteCancelResult.REQUESTED)
        caller.join()

        assertEquals("the in-flight cancel finished instead of being abandoned", RemoteCancelOutcome.REQUESTED, outcome)
    }
}
