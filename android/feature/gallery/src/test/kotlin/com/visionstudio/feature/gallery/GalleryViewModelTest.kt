package com.visionstudio.feature.gallery

import com.visionstudio.core.data.generation.GenerationEvent
import com.visionstudio.core.data.generation.GenerationRepository
import com.visionstudio.core.data.video.Clock
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import com.visionstudio.core.network.engine.BinaryPayload
import com.visionstudio.feature.gallery.model.GalleryFilter
import com.visionstudio.feature.gallery.model.GridEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {

    private val library = MutableStateFlow<List<MediaItem>>(emptyList())
    private val deleted = mutableListOf<String>()
    private val now = 1_800_000_000_000L

    private val repository = object : GenerationRepository {
        override fun generate(slot: EngineSlot, parameters: GenerationParameters, sourceImage: BinaryPayload?, mask: BinaryPayload?): Flow<GenerationEvent> = emptyFlow()
        override fun observeRecent(slot: EngineSlot, limit: Int): Flow<List<MediaItem>> = library
        override fun observeAll(): Flow<List<MediaItem>> = library
        override suspend fun delete(item: MediaItem) {
            deleted += item.id
            library.value = library.value.filterNot { it.id == item.id }
        }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = GalleryViewModel(repository, Clock { now }, UnconfinedTestDispatcher())

    private fun item(id: String, type: MediaType = MediaType.IMAGE, ageMillis: Long = 0) = MediaItem(
        id = id, taskId = "t", type = type, localUri = "file:///$id", width = 100, height = 100,
        durationMillis = if (type == MediaType.VIDEO) 5_000 else null, sizeBytes = 1,
        parameters = GenerationParameters(prompt = id, modelId = "m"), createdAtEpochMillis = now - ageMillis,
    )

    private fun GalleryViewModel.collecting(block: (() -> GalleryUiState) -> Unit) = runTest(UnconfinedTestDispatcher()) {
        val job = backgroundScope.launch { uiState.collect { } }
        block { uiState.value }
        job.cancel()
    }

    private fun GalleryUiState.tileIds() = entries.filterIsInstance<GridEntry.Tile>().map { it.item.id }

    @Test
    fun `starts not loaded, then shows the library`() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        assertFalse("nothing is claimed before data arrives", vm.uiState.value.loaded)

        library.value = listOf(item("a"), item("b", MediaType.VIDEO))
        backgroundScope.launch { vm.uiState.collect { } }

        val state = vm.uiState.value
        assertTrue(state.loaded)
        assertEquals(2, state.totalCount)
        assertEquals(1, state.imageCount)
        assertEquals(1, state.videoCount)
    }

    @Test
    fun `an empty library is distinguished from an empty filter`() {
        library.value = emptyList()
        viewModel().collecting { state ->
            assertTrue(state().isLibraryEmpty)
            assertFalse(state().isFilterEmpty)
        }

        library.value = listOf(item("img"))
        val vm = viewModel()
        vm.collecting { state ->
            vm.onIntent(GalleryIntent.SetFilter(GalleryFilter.VIDEOS))
            assertTrue("library has items but none are videos", state().isFilterEmpty)
            assertFalse(state().isLibraryEmpty)
            assertEquals("counts ignore the filter", 1, state().totalCount)
        }
    }

    @Test
    fun `filters narrow the grid and counts stay put`() {
        library.value = listOf(item("img1", ageMillis = 3), item("vid1", MediaType.VIDEO, ageMillis = 2), item("img2", ageMillis = 1))
        val vm = viewModel()
        vm.collecting { state ->
            assertEquals(listOf("img2", "vid1", "img1"), state().tileIds())

            vm.onIntent(GalleryIntent.SetFilter(GalleryFilter.IMAGES))
            assertEquals(listOf("img2", "img1"), state().tileIds())
            assertEquals(GalleryFilter.IMAGES, state().filter)

            vm.onIntent(GalleryIntent.SetFilter(GalleryFilter.VIDEOS))
            assertEquals(listOf("vid1"), state().tileIds())
            assertEquals(3, state().totalCount)
        }
    }

    @Test
    fun `new creations appear live`() {
        library.value = listOf(item("old", ageMillis = 10))
        viewModel().collecting { state ->
            library.value = listOf(item("fresh"), item("old", ageMillis = 10))
            assertEquals(listOf("fresh", "old"), state().tileIds())
        }
    }

    @Test
    fun `opening and closing tracks the selection`() {
        library.value = listOf(item("a"), item("b"))
        val vm = viewModel()
        vm.collecting { state ->
            assertNull(state().selected)

            vm.onIntent(GalleryIntent.Open("b"))
            assertEquals("b", state().selected?.id)

            vm.onIntent(GalleryIntent.Close)
            assertNull(state().selected)
        }
    }

    @Test
    fun `opening an unknown id selects nothing`() {
        library.value = listOf(item("a"))
        val vm = viewModel()
        vm.collecting { state ->
            vm.onIntent(GalleryIntent.Open("ghost"))
            assertNull(state().selected)
        }
    }

    @Test
    fun `an open item that disappears closes the sheet by itself`() {
        library.value = listOf(item("a"), item("b"))
        val vm = viewModel()
        vm.collecting { state ->
            vm.onIntent(GalleryIntent.Open("a"))
            assertEquals("a", state().selected?.id)

            library.value = listOf(item("b")) // deleted from somewhere else

            assertNull("no stale sheet pointing at a deleted asset", state().selected)
        }
    }

    @Test
    fun `deleting removes the item from the library and closes the sheet`() {
        library.value = listOf(item("a"), item("b"))
        val vm = viewModel()
        vm.collecting { state ->
            vm.onIntent(GalleryIntent.Open("a"))

            vm.onIntent(GalleryIntent.Delete("a"))

            assertEquals(listOf("a"), deleted)
            assertNull(state().selected)
            assertEquals(listOf("b"), state().tileIds())
        }
    }

    @Test
    fun `delete only acts on the item that is actually open`() {
        library.value = listOf(item("a"), item("b"))
        val vm = viewModel()
        vm.collecting { state ->
            vm.onIntent(GalleryIntent.Open("a"))

            vm.onIntent(GalleryIntent.Delete("b")) // not the open one
            assertTrue("nothing deleted", deleted.isEmpty())
            assertEquals("a", state().selected?.id)

            vm.onIntent(GalleryIntent.Close)
            vm.onIntent(GalleryIntent.Delete("a")) // nothing open at all
            assertTrue(deleted.isEmpty())
        }
    }
}
