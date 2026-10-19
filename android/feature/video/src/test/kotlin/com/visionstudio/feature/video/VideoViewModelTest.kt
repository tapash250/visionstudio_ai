package com.visionstudio.feature.video

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.visionstudio.core.common.result.Result
import com.visionstudio.core.data.generation.GenerationEvent
import com.visionstudio.core.data.generation.GenerationRepository
import com.visionstudio.core.data.generation.MediaCounts
import com.visionstudio.core.data.media.MediaInspector
import com.visionstudio.core.data.media.SourceImageLoader
import com.visionstudio.core.data.video.CancelledJob
import com.visionstudio.core.data.video.VideoJob
import com.visionstudio.core.data.video.VideoJobRepository
import com.visionstudio.core.data.video.VideoJobStatus
import com.visionstudio.core.model.AspectRatio
import com.visionstudio.core.model.CameraMotion
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.core.network.engine.BinaryPayload
import com.visionstudio.core.security.KeystoreRecovery
import com.visionstudio.core.security.SecurePreferencesRepository
import com.visionstudio.feature.video.work.VideoJobLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VideoViewModelTest {

    @get:Rule val temp = TemporaryFolder()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val launcher = FakeLauncher()
    private val jobs = FakeJobs()
    private val generations = FakeGenerations()
    private val preferences = FakePreferences()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = VideoViewModel(
        launcher = launcher,
        jobs = jobs,
        generations = generations,
        securePreferences = preferences,
        loader = SourceImageLoader(context, Dispatchers.IO, Dispatchers.Default),
        inspector = MediaInspector(Dispatchers.IO, Dispatchers.Default),
    )

    private fun VideoViewModel.await(predicate: (VideoUiState) -> Boolean): VideoUiState =
        runBlocking { withTimeout(AWAIT_MILLIS) { uiState.first(predicate) } }

    private fun photo(width: Int = 400, height: Int = 300): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2255AA.toInt()) }
        val file = File(temp.root, "photo-$width-$height.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private fun item(id: String = "item-1") = MediaItem(
        id = id, taskId = "t", type = MediaType.VIDEO, localUri = "file:///v.mp4", width = 1280, height = 720,
        durationMillis = 5_000, sizeBytes = 1, parameters = GenerationParameters(prompt = "p", modelId = "m"), createdAtEpochMillis = 1,
    )

    private fun job(id: String, status: VideoJobStatus, resultItemId: String? = null, progress: Float? = null) = VideoJob(
        id = id, status = status, parameters = GenerationParameters(prompt = "prompt-$id", modelId = "m"), progress = progress,
        message = null, error = null, resultItemId = resultItemId, hasSourceImage = false,
        createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
    )

    @Test
    fun `generate needs an engine, a prompt and (for image-to-video) a photo`() {
        val vm = viewModel()
        vm.await { it.engineConfigured }
        assertFalse("no prompt", vm.uiState.value.canGenerate)

        vm.onIntent(VideoIntent.EditPrompt("a koi"))
        assertTrue(vm.uiState.value.canGenerate)

        vm.onIntent(VideoIntent.SetMode(VideoMode.IMAGE_TO_VIDEO))
        assertFalse("image-to-video without a photo", vm.uiState.value.canGenerate)

        vm.onIntent(VideoIntent.PhotoPicked(photo()))
        vm.await { it.photo != null }
        assertTrue(vm.uiState.value.canGenerate)

        vm.onIntent(VideoIntent.EditPrompt("   "))
        assertFalse("blank prompt", vm.uiState.value.canGenerate)
    }

    @Test
    fun `text to video builds parameters from the controls and focuses the new job`() {
        val vm = viewModel()
        vm.await { it.engineConfigured }
        vm.onIntent(VideoIntent.EditPrompt("  a koi in neon rain  "))
        vm.onIntent(VideoIntent.SetAspect(AspectRatio.PORTRAIT_9_16))
        vm.onIntent(VideoIntent.SetMotion(CameraMotion.ORBIT))
        vm.onIntent(VideoIntent.SetDuration(8))

        vm.onIntent(VideoIntent.Generate)

        val launched = launcher.launched.single()
        assertEquals("a koi in neon rain", launched.parameters.prompt)
        assertEquals(AspectRatio.PORTRAIT_9_16, launched.parameters.aspectRatio)
        assertEquals(CameraMotion.ORBIT, launched.parameters.cameraMotion)
        assertEquals(8, launched.parameters.durationSeconds)
        assertEquals("flux-video", launched.parameters.modelId)
        assertNotNull(launched.parameters.seed)
        assertNull(launched.parameters.sourceImageUri)
        assertNull("text-to-video sends no photo", launched.source)
        assertEquals(Focus.Job("launched-1"), vm.uiState.value.focus)
    }

    @Test
    fun `a static camera is sent as no camera motion at all`() {
        val vm = viewModel()
        vm.await { it.engineConfigured }
        vm.onIntent(VideoIntent.EditPrompt("a still life"))

        vm.onIntent(VideoIntent.Generate)

        assertNull(launcher.launched.single().parameters.cameraMotion)
    }

    @Test
    fun `image to video sends the photo and follows its shape, not the frame chip`() {
        val vm = viewModel()
        vm.await { it.engineConfigured }
        vm.onIntent(VideoIntent.SetAspect(AspectRatio.PORTRAIT_9_16)) // should be ignored for I2V
        val uri = photo(400, 300)
        vm.onIntent(VideoIntent.PhotoPicked(uri))
        vm.await { it.photo != null }
        vm.onIntent(VideoIntent.EditPrompt("the camera pushes in"))

        assertEquals("picking a photo switches mode", VideoMode.IMAGE_TO_VIDEO, vm.uiState.value.controls.mode)
        vm.onIntent(VideoIntent.Generate)

        val launched = launcher.launched.single()
        assertEquals(AspectRatio.LANDSCAPE_4_3, launched.parameters.aspectRatio)
        assertEquals(uri.toString(), launched.parameters.sourceImageUri)
        assertEquals("image/png", launched.source!!.mimeType)
        assertTrue(launched.source.bytes.isNotEmpty())
    }

    @Test
    fun `text mode ignores a leftover photo`() {
        val vm = viewModel()
        vm.await { it.engineConfigured }
        vm.onIntent(VideoIntent.PhotoPicked(photo()))
        vm.await { it.photo != null }
        vm.onIntent(VideoIntent.SetMode(VideoMode.TEXT_TO_VIDEO))
        vm.onIntent(VideoIntent.EditPrompt("a fresh idea"))

        vm.onIntent(VideoIntent.Generate)

        assertNull(launcher.launched.single().source)
        assertNull(launcher.launched.single().parameters.sourceImageUri)
    }

    @Test
    fun `removing the photo clears it`() {
        val vm = viewModel()
        vm.onIntent(VideoIntent.PhotoPicked(photo()))
        vm.await { it.photo != null }

        vm.onIntent(VideoIntent.RemovePhoto)

        assertNull(vm.uiState.value.photo)
    }

    @Test
    fun `an unreadable photo shows a notice and leaves state usable`() {
        val vm = viewModel()
        val bogus = Uri.fromFile(File(temp.root, "x.txt").apply { writeText("hello") })

        vm.onIntent(VideoIntent.PhotoPicked(bogus))
        val state = vm.await { it.notice != null }

        assertNull(state.photo)
        assertFalse(state.photoLoading)
    }

    @Test
    fun `reopening mid-render focuses the running job`() {
        jobs.flow.value = listOf(job("running", VideoJobStatus.POLLING, progress = 0.4f))

        val state = viewModel().uiState.value

        assertEquals(Focus.Job("running"), state.focus)
        assertEquals(0.4f, state.jobs.single().progress!!, 0f)
    }

    @Test
    fun `when the watched job finishes the stage switches to its video`() {
        jobs.flow.value = listOf(job("a", VideoJobStatus.POLLING))
        generations.recent.value = emptyList()
        val vm = viewModel()
        assertEquals(Focus.Job("a"), vm.uiState.value.focus)

        generations.recent.value = listOf(item("result-9"))
        jobs.flow.value = listOf(job("a", VideoJobStatus.COMPLETED, resultItemId = "result-9"))

        val state = vm.uiState.value
        assertEquals(Focus.Item("result-9"), state.focus)
        assertEquals("completed jobs leave the render list", emptyList<JobUi>(), state.jobs)
        assertEquals("result-9", state.focusedItem!!.id)
    }

    @Test
    fun `a failed job stays listed so it can be retried or dismissed`() {
        jobs.flow.value = listOf(job("bad", VideoJobStatus.FAILED))

        val state = viewModel().uiState.value

        assertEquals(listOf(VideoJobStatus.FAILED), state.jobs.map { it.status })
        assertEquals(0, state.activeJobs)
    }

    @Test
    fun `dismissing the watched job returns the stage to empty`() {
        jobs.flow.value = listOf(job("a", VideoJobStatus.FAILED))
        val vm = viewModel()
        vm.onIntent(VideoIntent.FocusJob("a"))

        jobs.flow.value = emptyList()

        assertEquals(Focus.None, vm.uiState.value.focus)
    }

    @Test
    fun `cancel, retry and dismiss reach the launcher`() {
        launcher.retryResult = true
        jobs.flow.value = listOf(job("a", VideoJobStatus.FAILED))
        val vm = viewModel()

        vm.onIntent(VideoIntent.CancelJob("a"))
        vm.onIntent(VideoIntent.RetryJob("a"))
        vm.onIntent(VideoIntent.DismissJob("a"))

        assertEquals(listOf("a"), launcher.cancelled)
        assertEquals(listOf("a"), launcher.retried)
        assertEquals(listOf("a"), launcher.dismissed)
        assertEquals(Focus.Job("a"), vm.uiState.value.focus)
    }

    @Test
    fun `a refused retry explains itself`() {
        launcher.retryResult = false
        val vm = viewModel()

        vm.onIntent(VideoIntent.RetryJob("gone"))

        assertTrue(vm.uiState.value.notice!!.contains("can't be retried"))
    }

    // region fakes

    private class Launched(val parameters: GenerationParameters, val source: BinaryPayload?)

    private class FakeLauncher : VideoJobLauncher {
        val launched = mutableListOf<Launched>()
        val cancelled = mutableListOf<String>()
        val retried = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        var retryResult = true

        override suspend fun launch(parameters: GenerationParameters, sourceImage: BinaryPayload?): String {
            launched += Launched(parameters, sourceImage)
            return "launched-${launched.size}"
        }

        override suspend fun cancel(jobId: String) { cancelled += jobId }
        override suspend fun retry(jobId: String): Boolean { retried += jobId; return retryResult }
        override suspend fun dismiss(jobId: String) { dismissed += jobId }
    }

    private class FakeJobs : VideoJobRepository {
        val flow = MutableStateFlow<List<VideoJob>>(emptyList())
        override suspend fun create(parameters: GenerationParameters, sourceImage: BinaryPayload?): VideoJob = error("not used")
        override fun observeJobs(limit: Int): Flow<List<VideoJob>> = flow
        override suspend fun get(id: String): VideoJob? = flow.value.firstOrNull { it.id == id }
        override suspend fun cancel(id: String): CancelledJob? = null
        override suspend fun retry(id: String) = true
        override suspend fun fail(id: String, message: String) = Unit
        override suspend fun dismiss(id: String) = Unit
    }

    private class FakeGenerations : GenerationRepository {
        val recent = MutableStateFlow<List<MediaItem>>(emptyList())
        override fun generate(slot: EngineSlot, parameters: GenerationParameters, sourceImage: BinaryPayload?, mask: BinaryPayload?): Flow<GenerationEvent> = flowOf()
        override fun observeRecent(slot: EngineSlot, limit: Int): Flow<List<MediaItem>> = recent
        override fun observeLibrary(type: MediaType?, limit: Int): Flow<List<MediaItem>> = recent
        override fun observeCounts(): Flow<MediaCounts> = flowOf(MediaCounts())
        override fun observeItem(id: String): Flow<MediaItem?> = flowOf(null)
        override suspend fun delete(item: MediaItem) = Unit
    }

    private class FakePreferences : SecurePreferencesRepository {
        private val config = EngineConfig(EngineSlot.VIDEO_STUDIO, ProviderProtocol.REST, "https://queue.fal.run", "flux-video")
        override val recoveryEvents: SharedFlow<KeystoreRecovery> = MutableSharedFlow()
        override suspend fun getApiKey(slot: EngineSlot) = Result.Success<String?>("k")
        override suspend fun hasApiKey(slot: EngineSlot) = Result.Success(true)
        override suspend fun setApiKey(slot: EngineSlot, apiKey: String) = Result.Success(Unit)
        override suspend fun clearApiKey(slot: EngineSlot) = Result.Success(Unit)
        override suspend fun getEngineConfig(slot: EngineSlot) = Result.Success(config)
        override suspend fun saveEngineConfig(config: EngineConfig) = Result.Success(Unit)
        override fun observeEngineConfigs(): Flow<Map<EngineSlot, EngineConfig>> =
            flowOf(EngineSlot.entries.associateWith { if (it == EngineSlot.VIDEO_STUDIO) config else EngineConfig.unconfigured(it) })
        override suspend fun clearAll() = Result.Success(Unit)
    }

    // endregion

    private companion object {
        const val AWAIT_MILLIS = 15_000L
    }
}
