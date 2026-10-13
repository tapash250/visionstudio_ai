package com.visionstudio.feature.imageedit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.result.Result
import com.visionstudio.core.data.generation.GenerationEvent
import com.visionstudio.core.data.generation.GenerationRepository
import com.visionstudio.core.data.media.MediaInspector
import com.visionstudio.core.data.media.SourceImageLoader
import com.visionstudio.core.model.AspectRatio
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.core.network.engine.BinaryPayload
import com.visionstudio.core.security.KeystoreRecovery
import com.visionstudio.core.security.SecurePreferencesRepository
import com.visionstudio.feature.imageedit.mask.MaskRasterizer
import com.visionstudio.feature.imageedit.mask.MaskStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
class EditViewModelTest {

    @get:Rule val temp = TemporaryFolder()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val repository = FakeRepository()
    private val preferences = FakePreferences()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = EditViewModel(
        repository = repository,
        securePreferences = preferences,
        loader = SourceImageLoader(context, Dispatchers.IO, Dispatchers.Default),
        inspector = MediaInspector(Dispatchers.IO, Dispatchers.Default),
        rasterizer = MaskRasterizer(Dispatchers.Default),
    )

    private fun photo(name: String, width: Int, height: Int): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2255AA.toInt()) }
        val file = File(temp.root, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private fun dot(x: Float = 0.5f, y: Float = 0.5f, erase: Boolean = false, diameter: Float = 0.3f) =
        MaskStroke(floatArrayOf(x, y), diameter, erase)

    /** Blocks (in real time, since decoding hops to IO threads) until the state matches. */
    private fun EditViewModel.awaitState(predicate: (EditUiState) -> Boolean): EditUiState =
        runBlocking { withTimeout(AWAIT_MILLIS) { uiState.first(predicate) } }

    private fun EditViewModel.loadPhoto(uri: Uri = photo("p.png", 400, 300)): EditViewModel {
        onIntent(EditIntent.PhotoPicked(uri))
        awaitState { it.phase == EditPhase.Editing }
        return this
    }

    private fun EditViewModel.readyToRepaint(): EditViewModel {
        loadPhoto()
        onIntent(EditIntent.CommitStroke(dot()))
        onIntent(EditIntent.EditPrompt("a red balloon"))
        return this
    }

    @Test
    fun `picking a photo enters editing with an empty mask`() {
        val vm = viewModel().loadPhoto(photo("wide.png", 400, 300))

        val state = vm.uiState.value
        assertEquals(EditPhase.Editing, state.phase)
        assertEquals(400, state.source!!.width)
        assertEquals(300, state.source!!.height)
        assertTrue(state.history.isEmpty)
    }

    @Test
    fun `dismissing the picker changes nothing`() {
        val vm = viewModel()
        vm.onIntent(EditIntent.PhotoPicked(null))
        assertEquals(EditPhase.Empty, vm.uiState.value.phase)
    }

    @Test
    fun `an unreadable file shows an error and returns to the picker`() {
        val vm = viewModel()
        val bogus = Uri.fromFile(File(temp.root, "notes.txt").apply { writeText("hello") })

        vm.onIntent(EditIntent.PhotoPicked(bogus))
        val state = vm.awaitState { it.error != null }

        assertEquals(EditPhase.Empty, state.phase)
        assertNull(state.source)
    }

    @Test
    fun `repaint needs engine, prompt and a painted area`() {
        val vm = viewModel().loadPhoto()
        vm.awaitState { it.engineConfigured }
        assertFalse(vm.uiState.value.canRepaint)

        vm.onIntent(EditIntent.EditPrompt("a red balloon"))
        assertFalse("still no mask", vm.uiState.value.canRepaint)

        vm.onIntent(EditIntent.CommitStroke(dot()))
        assertTrue(vm.uiState.value.canRepaint)

        vm.onIntent(EditIntent.Undo)
        assertFalse(vm.uiState.value.canRepaint)
        vm.onIntent(EditIntent.Redo)
        assertTrue(vm.uiState.value.canRepaint)

        vm.onIntent(EditIntent.EditPrompt("   "))
        assertFalse("blank prompt", vm.uiState.value.canRepaint)
    }

    @Test
    fun `repaint sends source, a binary mask, strength and the matching aspect ratio`() {
        repository.script = flow { emit(GenerationEvent.Completed(listOf(resultItem()))) }
        val vm = viewModel().readyToRepaint()
        vm.onIntent(EditIntent.SetStrength(0.6f))

        vm.onIntent(EditIntent.Repaint)
        val state = vm.awaitState { it.phase is EditPhase.Result }

        assertNotNull(state.phase as EditPhase.Result)
        val call = repository.calls.single()
        assertEquals(EngineSlot.IMAGE_EDIT, call.slot)
        assertEquals("a red balloon", call.parameters.prompt)
        assertEquals(0.6f, call.parameters.strength!!, 0.0001f)
        assertEquals(AspectRatio.LANDSCAPE_4_3, call.parameters.aspectRatio)
        assertNotNull(call.parameters.seed)

        val source = BitmapFactory.decodeByteArray(call.sourceImage!!.bytes, 0, call.sourceImage.bytes.size)
        val mask = BitmapFactory.decodeByteArray(call.mask!!.bytes, 0, call.mask.bytes.size)
        assertEquals("mask must match the source size exactly", source.width, mask.width)
        assertEquals(source.height, mask.height)
        assertEquals(WHITE, mask.getPixel(mask.width / 2, mask.height / 2))
        assertEquals(BLACK, mask.getPixel(2, 2))
    }

    @Test
    fun `a mask that was fully erased is rejected without calling the provider`() {
        val vm = viewModel().loadPhoto()
        vm.onIntent(EditIntent.EditPrompt("a red balloon"))
        vm.onIntent(EditIntent.CommitStroke(dot(diameter = 0.2f)))
        vm.onIntent(EditIntent.CommitStroke(dot(erase = true, diameter = 0.4f)))
        assertTrue("the UI still thinks something is painted", vm.uiState.value.canRepaint)

        vm.onIntent(EditIntent.Repaint)
        val state = vm.awaitState { it.error != null }

        assertEquals(EditPhase.Editing, state.phase)
        assertTrue(state.error!!.contains("Nothing is selected"))
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun `provider failure returns to editing with a readable error and keeps the mask`() {
        repository.script = flow { throw VisionStudioException.AuthenticationException(EngineSlot.IMAGE_EDIT, 401) }
        val vm = viewModel().readyToRepaint()

        vm.onIntent(EditIntent.Repaint)
        val state = vm.awaitState { it.error != null }

        assertEquals(EditPhase.Editing, state.phase)
        assertTrue(state.error!!.contains("API key"))
        assertEquals(1, state.history.strokes.size)
    }

    @Test
    fun `progress never runs backwards`() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        repository.script = flow {
            emit(GenerationEvent.Progress(0.6f, "Refining"))
            emit(GenerationEvent.Progress(0.3f, null))
            gate.await()
        }
        val vm = viewModel().readyToRepaint()

        vm.onIntent(EditIntent.Repaint)
        val rendering = vm.awaitState { (it.phase as? EditPhase.Rendering)?.progress == 0.6f }.phase as EditPhase.Rendering

        assertEquals(0.6f, rendering.progress!!, 0f)
        assertEquals("Refining", rendering.message)
        vm.onIntent(EditIntent.Cancel)
        gate.cancel()
    }

    @Test
    fun `cancelling a render returns to editing`() {
        repository.script = flow {
            emit(GenerationEvent.Progress(0.2f, null))
            kotlinx.coroutines.awaitCancellation()
        }
        val vm = viewModel().readyToRepaint()
        vm.onIntent(EditIntent.Repaint)
        vm.awaitState { it.phase is EditPhase.Rendering && (it.phase as EditPhase.Rendering).progress == 0.2f }

        vm.onIntent(EditIntent.Cancel)
        val state = vm.awaitState { it.phase == EditPhase.Editing }

        assertNull(state.error)
        assertEquals(1, state.history.strokes.size)
    }

    @Test
    fun `mask edits are ignored while rendering`() {
        repository.script = flow { kotlinx.coroutines.awaitCancellation() }
        val vm = viewModel().readyToRepaint()
        vm.onIntent(EditIntent.Repaint)
        vm.awaitState { it.rendering }

        vm.onIntent(EditIntent.CommitStroke(dot(0.1f, 0.1f)))
        vm.onIntent(EditIntent.ClearMask)

        assertEquals(1, vm.uiState.value.history.strokes.size)
        vm.onIntent(EditIntent.Cancel)
    }

    @Test
    fun `keep editing preserves the mask and use result starts a fresh photo`() {
        val resultFile = temp.newFile("result.png")
        FileOutputStream(resultFile).use {
            Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        repository.script = flow { emit(GenerationEvent.Completed(listOf(resultItem(Uri.fromFile(resultFile).toString())))) }
        val vm = viewModel().readyToRepaint()
        vm.onIntent(EditIntent.Repaint)
        vm.awaitState { it.phase is EditPhase.Result }

        vm.onIntent(EditIntent.KeepEditing)
        assertEquals(EditPhase.Editing, vm.uiState.value.phase)
        assertEquals(1, vm.uiState.value.history.strokes.size)

        vm.onIntent(EditIntent.Repaint)
        vm.awaitState { it.phase is EditPhase.Result }
        vm.onIntent(EditIntent.UseResultAsSource)
        val state = vm.awaitState { it.phase == EditPhase.Editing && it.source?.width == 120 }

        assertEquals(160, state.source!!.height)
        assertTrue("mask is reset for the new photo", state.history.isEmpty)
    }

    // region fakes

    private class Call(val slot: EngineSlot, val parameters: GenerationParameters, val sourceImage: BinaryPayload?, val mask: BinaryPayload?)

    private class FakeRepository : GenerationRepository {
        val calls = mutableListOf<Call>()
        var script: Flow<GenerationEvent> = emptyFlow()

        override fun generate(slot: EngineSlot, parameters: GenerationParameters, sourceImage: BinaryPayload?, mask: BinaryPayload?): Flow<GenerationEvent> {
            calls += Call(slot, parameters, sourceImage, mask)
            return script
        }

        override fun observeRecent(slot: EngineSlot, limit: Int): Flow<List<MediaItem>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<MediaItem>> = flowOf(emptyList())
        override suspend fun delete(item: MediaItem) = Unit
    }

    private class FakePreferences : SecurePreferencesRepository {
        private val config = EngineConfig(EngineSlot.IMAGE_EDIT, ProviderProtocol.REST, "https://api.example.com/v1", "flux-fill")
        override val recoveryEvents: SharedFlow<KeystoreRecovery> = MutableSharedFlow()
        override suspend fun getApiKey(slot: EngineSlot) = Result.Success<String?>("k")
        override suspend fun hasApiKey(slot: EngineSlot) = Result.Success(true)
        override suspend fun setApiKey(slot: EngineSlot, apiKey: String) = Result.Success(Unit)
        override suspend fun clearApiKey(slot: EngineSlot) = Result.Success(Unit)
        override suspend fun getEngineConfig(slot: EngineSlot) = Result.Success(config)
        override suspend fun saveEngineConfig(config: EngineConfig) = Result.Success(Unit)
        override fun observeEngineConfigs(): Flow<Map<EngineSlot, EngineConfig>> =
            flowOf(EngineSlot.entries.associateWith { if (it == EngineSlot.IMAGE_EDIT) config else EngineConfig.unconfigured(it) })
        override suspend fun clearAll() = Result.Success(Unit)
    }

    private fun resultItem(uri: String = "file:///nonexistent/result.png") = MediaItem(
        id = "item-1",
        taskId = "task-1",
        type = com.visionstudio.core.model.MediaType.IMAGE,
        localUri = uri,
        width = 400,
        height = 300,
        sizeBytes = 1,
        parameters = GenerationParameters(prompt = "a red balloon", modelId = "flux-fill"),
        createdAtEpochMillis = 1L,
    )

    // endregion

    private companion object {
        const val WHITE = -0x1
        const val BLACK = -0x1000000
        const val AWAIT_MILLIS = 15_000L
    }
}
