package com.visionstudio.feature.gallery

import android.content.Intent
import android.net.Uri
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.export.ExportedMedia
import com.visionstudio.core.common.export.MediaExporter
import com.visionstudio.core.common.result.Result
import com.visionstudio.core.data.generation.GenerationEvent
import com.visionstudio.core.data.generation.GenerationRepository
import com.visionstudio.core.data.generation.MediaCounts
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
import kotlinx.coroutines.flow.map
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryViewModelTest {

    private val library = MutableStateFlow<List<MediaItem>>(emptyList())
    private val deleted = mutableListOf<String>()
    private val now = 1_800_000_000_000L

    /** Every window the ViewModel asked for, in order, as (type, limit). */
    private val windowRequests = mutableListOf<Pair<MediaType?, Int>>()

    /** Mirrors the Room queries: newest first with id as tie-break, filter before limit. */
    private val repository = object : GenerationRepository {
        override fun generate(slot: EngineSlot, parameters: GenerationParameters, sourceImage: BinaryPayload?, mask: BinaryPayload?): Flow<GenerationEvent> = emptyFlow()
        override fun observeRecent(slot: EngineSlot, limit: Int): Flow<List<MediaItem>> = library
        override fun observeLibrary(type: MediaType?, limit: Int): Flow<List<MediaItem>> {
            windowRequests += type to limit
            return library.map { all ->
                all.filter { type == null || it.type == type }
                    .sortedWith(compareByDescending<MediaItem> { it.createdAtEpochMillis }.thenByDescending { it.id })
                    .take(limit)
            }
        }
        override fun observeCounts(): Flow<MediaCounts> = library.map { all ->
            MediaCounts(images = all.count { it.type == MediaType.IMAGE }, videos = all.count { it.type == MediaType.VIDEO })
        }
        override fun observeItem(id: String): Flow<MediaItem?> = library.map { all -> all.firstOrNull { it.id == id } }
        override suspend fun delete(item: MediaItem) {
            deleted += item.id
            library.value = library.value.filterNot { it.id == item.id }
        }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val exporter = FakeExporter()

    private fun viewModel() = GalleryViewModel(repository, exporter, Clock { now }, UnconfinedTestDispatcher())

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

    // region paging

    private fun manyImages(count: Int) = (1..count).map { item("img-%03d".format(it), ageMillis = it.toLong()) }

    @Test
    fun `only the first page is loaded, while counts cover the whole library`() {
        library.value = manyImages(100)
        viewModel().collecting { state ->
            assertEquals(GalleryViewModel.PAGE_SIZE, state().tileIds().size)
            assertEquals(100, state().totalCount)
            assertFalse(state().endReached)
            assertTrue("the grid says there is more", state().entries.last() == GridEntry.More)
        }
    }

    @Test
    fun `loading more appends the next page in order, then stops at the end`() {
        library.value = manyImages(100)
        val vm = viewModel()
        vm.collecting { state ->
            val firstPage = state().tileIds()

            vm.onIntent(GalleryIntent.LoadMore)
            assertEquals(80, state().tileIds().size)
            assertEquals("earlier tiles never move", firstPage, state().tileIds().take(40))

            vm.onIntent(GalleryIntent.LoadMore)
            assertEquals(100, state().tileIds().size)
            assertTrue(state().endReached)
            assertFalse(state().entries.contains(GridEntry.More))
            assertEquals(manyImages(100).map { it.id }, state().tileIds())

            val requestsAtEnd = windowRequests.size
            vm.onIntent(GalleryIntent.LoadMore)
            assertEquals("nothing is requested past the end", requestsAtEnd, windowRequests.size)
        }
    }

    @Test
    fun `a library that fits in one page is complete straight away`() {
        library.value = manyImages(5)
        viewModel().collecting { state ->
            assertTrue(state().endReached)
            assertEquals(listOf(5), state().entries.filterIsInstance<GridEntry.Header>().map { it.count })
        }
    }

    @Test
    fun `the oldest day's count is withheld until that day is fully loaded`() {
        // 60 items on one day: the first page shows 40 of them, so "40" would be a wrong count.
        library.value = manyImages(60)
        val vm = viewModel()
        vm.collecting { state ->
            assertEquals(listOf<Int?>(null), state().entries.filterIsInstance<GridEntry.Header>().map { it.count })
            vm.onIntent(GalleryIntent.LoadMore)
            assertEquals(listOf<Int?>(60), state().entries.filterIsInstance<GridEntry.Header>().map { it.count })
        }
    }

    @Test
    fun `changing the filter starts again from its first page`() {
        library.value = manyImages(100) + (1..50).map { item("vid-%03d".format(it), MediaType.VIDEO, ageMillis = 1_000L + it) }
        val vm = viewModel()
        vm.collecting { state ->
            vm.onIntent(GalleryIntent.LoadMore)
            assertEquals(80, state().tileIds().size)

            vm.onIntent(GalleryIntent.SetFilter(GalleryFilter.VIDEOS))

            assertEquals(GalleryViewModel.PAGE_SIZE, state().tileIds().size)
            assertTrue(state().tileIds().all { it.startsWith("vid-") })
            assertEquals(MediaType.VIDEO to GalleryViewModel.PAGE_SIZE, windowRequests.last())
        }
    }

    @Test
    fun `a new creation appears at the top without losing the loaded pages`() {
        library.value = manyImages(100)
        val vm = viewModel()
        vm.collecting { state ->
            vm.onIntent(GalleryIntent.LoadMore)
            library.value = listOf(item("fresh", ageMillis = 0)) + library.value

            assertEquals("fresh", state().tileIds().first())
            assertEquals(80, state().tileIds().size)
            assertEquals(101, state().totalCount)
        }
    }

    @Test
    fun `an open item stays open even when it is outside the current filter`() {
        library.value = listOf(item("img"), item("vid", MediaType.VIDEO))
        val vm = viewModel()
        vm.collecting { state ->
            vm.onIntent(GalleryIntent.Open("img"))
            vm.onIntent(GalleryIntent.SetFilter(GalleryFilter.VIDEOS))

            assertEquals("img", state().selected?.id)
        }
    }

    // endregion

    // region export and share

    @Test
    fun `saving reports where the file went and whether settings were embedded`() {
        library.value = listOf(item("a"))
        exporter.saveResults += Result.Success(ExportedMedia(Uri.parse("content://media/1"), "a.png", "image/png", "Pictures/VisionStudio", metadataEmbedded = true))
        val vm = viewModel()
        vm.collecting { state ->
            vm.onIntent(GalleryIntent.Open("a"))
            vm.onIntent(GalleryIntent.SaveToGallery)

            assertEquals(ExportState.Saved("Pictures/VisionStudio", metadataEmbedded = true), state().export)
            assertEquals(listOf("a"), exporter.saved)
        }
    }

    @Test
    fun `saving does nothing when no item is open`() {
        library.value = listOf(item("a"))
        val vm = viewModel()
        vm.collecting { state ->
            vm.onIntent(GalleryIntent.SaveToGallery)

            assertEquals(ExportState.Idle, state().export)
            assertTrue(exporter.saved.isEmpty())
        }
    }

    @Test
    fun `a failed save shows the reason and can be tried again`() {
        library.value = listOf(item("a"))
        exporter.saveResults += Result.Error(VisionStudioException.StorageException("Disk full"))
        exporter.saveResults += Result.Success(ExportedMedia(Uri.parse("content://media/2"), "a.png", "image/png", "Pictures/VisionStudio", false))
        val vm = viewModel()
        vm.collecting { state ->
            vm.onIntent(GalleryIntent.Open("a"))
            vm.onIntent(GalleryIntent.SaveToGallery)
            assertEquals(ExportState.Failed("Disk full"), state().export)

            vm.onIntent(GalleryIntent.SaveToGallery)
            assertEquals(ExportState.Saved("Pictures/VisionStudio", false), state().export)
        }
    }

    @Test
    fun `on old Android a missing permission asks for it, and granting repeats the save`() {
        library.value = listOf(item("a"))
        exporter.saveResults += Result.Error(VisionStudioException.PermissionRequiredException("android.permission.WRITE_EXTERNAL_STORAGE"))
        exporter.saveResults += Result.Success(ExportedMedia(Uri.parse("content://media/3"), "a.png", "image/png", "Pictures/VisionStudio", true))
        val vm = viewModel()
        vm.collecting { state ->
            vm.onIntent(GalleryIntent.Open("a"))
            vm.onIntent(GalleryIntent.SaveToGallery)
            assertEquals(ExportState.NeedsPermission("android.permission.WRITE_EXTERNAL_STORAGE"), state().export)

            vm.onIntent(GalleryIntent.StoragePermissionResult(granted = true))

            assertEquals(ExportState.Saved("Pictures/VisionStudio", true), state().export)
            assertEquals("the same item is saved after the grant", listOf("a", "a"), exporter.saved)
        }
    }

    @Test
    fun `refusing the permission explains why nothing was saved and does not retry`() {
        library.value = listOf(item("a"))
        exporter.saveResults += Result.Error(VisionStudioException.PermissionRequiredException("android.permission.WRITE_EXTERNAL_STORAGE"))
        val vm = viewModel()
        vm.collecting { state ->
            vm.onIntent(GalleryIntent.Open("a"))
            vm.onIntent(GalleryIntent.SaveToGallery)

            vm.onIntent(GalleryIntent.StoragePermissionResult(granted = false))

            assertTrue(state().export is ExportState.Failed)
            assertEquals("no second attempt after a refusal", 1, exporter.saved.size)
        }
    }

    @Test
    fun `sharing delivers the chooser to the screen exactly once`() = runTest(UnconfinedTestDispatcher()) {
        library.value = listOf(item("a"))
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        val received = mutableListOf<GalleryEvent>()
        backgroundScope.launch { vm.events.collect { received += it } }
        vm.onIntent(GalleryIntent.Open("a"))

        vm.onIntent(GalleryIntent.Share("Share it"))

        assertEquals(1, received.size)
        assertEquals("Share it", exporter.shareTitles.single())
        assertEquals(listOf("a"), exporter.shared)
    }

    @Test
    fun `a share that cannot be built shows an error instead of an event`() = runTest(UnconfinedTestDispatcher()) {
        library.value = listOf(item("a"))
        exporter.shareFailure = VisionStudioException.StorageException("The original file is gone")
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        val received = mutableListOf<GalleryEvent>()
        backgroundScope.launch { vm.events.collect { received += it } }
        vm.onIntent(GalleryIntent.Open("a"))

        vm.onIntent(GalleryIntent.Share(null))

        assertTrue(received.isEmpty())
        assertEquals(ExportState.Failed("The original file is gone"), vm.uiState.value.export)
    }

    @Test
    fun `opening another item or closing clears the previous export message`() {
        library.value = listOf(item("a"), item("b"))
        exporter.saveResults += Result.Success(ExportedMedia(Uri.parse("content://media/4"), "a.png", "image/png", "Pictures/VisionStudio", true))
        val vm = viewModel()
        vm.collecting { state ->
            vm.onIntent(GalleryIntent.Open("a"))
            vm.onIntent(GalleryIntent.SaveToGallery)
            assertTrue(state().export is ExportState.Saved)

            vm.onIntent(GalleryIntent.Open("b"))
            assertEquals("b's sheet must not claim it was saved", ExportState.Idle, state().export)

            vm.onIntent(GalleryIntent.Close)
            assertEquals(ExportState.Idle, state().export)
        }
    }

    // endregion

    private class FakeExporter : MediaExporter {
        val saveResults = ArrayDeque<Result<ExportedMedia>>()
        val saved = mutableListOf<String>()
        val shared = mutableListOf<String>()
        val shareTitles = mutableListOf<CharSequence?>()
        var shareFailure: VisionStudioException? = null

        override suspend fun exportToGallery(item: MediaItem, embedMetadata: Boolean): Result<ExportedMedia> {
            saved += item.id
            return saveResults.removeFirstOrNull() ?: Result.Error(VisionStudioException.UnknownException("unscripted"))
        }

        override suspend fun createShareIntent(item: MediaItem, chooserTitle: CharSequence?, includePrompt: Boolean): Result<Intent> {
            shared += item.id
            shareTitles += chooserTitle
            return shareFailure?.let { Result.Error(it) } ?: Result.Success(Intent(Intent.ACTION_CHOOSER))
        }
    }
}
