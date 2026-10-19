package com.visionstudio.feature.gallery

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Intent
import com.visionstudio.core.common.dispatchers.DefaultDispatcher
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.export.MediaExporter
import com.visionstudio.core.common.result.Result
import com.visionstudio.core.data.generation.GenerationRepository
import com.visionstudio.core.data.video.Clock
import com.visionstudio.core.model.MediaItem
import com.visionstudio.feature.gallery.model.GalleryFilter
import com.visionstudio.feature.gallery.model.GridEntry
import com.visionstudio.feature.gallery.model.buildGrid
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

/** Progress of "Save to gallery" for the open item. */
@Immutable
sealed interface ExportState {
    data object Idle : ExportState
    data object Saving : ExportState

    /** [folder] is where the user will find it, for example `Pictures/VisionStudio`. */
    data class Saved(val folder: String, val metadataEmbedded: Boolean) : ExportState
    data class Failed(val message: String) : ExportState

    /** Android 8-9 only: the OS permission must be granted, then the save repeated. */
    data class NeedsPermission(val permission: String) : ExportState
}

/** One-shot actions the screen must perform, which are not part of persistent state. */
sealed interface GalleryEvent {
    data class Share(val chooser: Intent) : GalleryEvent
}

@Immutable
data class GalleryUiState(
    val loaded: Boolean = false,
    val filter: GalleryFilter = GalleryFilter.ALL,
    val entries: List<GridEntry> = emptyList(),
    /** Total items in the library, ignoring the filter, to tell "empty library" from "empty filter". */
    val totalCount: Int = 0,
    val imageCount: Int = 0,
    val videoCount: Int = 0,
    val selected: MediaItem? = null,
    val export: ExportState = ExportState.Idle,
    /** Every item matching the filter is loaded; scrolling further loads nothing more. */
    val endReached: Boolean = false,
    /** A larger window was requested and hasn't arrived yet. */
    val isLoadingMore: Boolean = false,
) {
    val isLibraryEmpty: Boolean get() = loaded && totalCount == 0
    val isFilterEmpty: Boolean get() = loaded && totalCount > 0 && entries.isEmpty()
}

sealed interface GalleryIntent {
    data class SetFilter(val filter: GalleryFilter) : GalleryIntent
    data class Open(val id: String) : GalleryIntent
    data object Close : GalleryIntent
    data class Delete(val id: String) : GalleryIntent

    /** Copy the open item into the shared gallery. */
    data object SaveToGallery : GalleryIntent

    /** Open the system share sheet for the open item. */
    data class Share(val chooserTitle: CharSequence?) : GalleryIntent

    /** The user answered the storage-permission prompt (Android 8-9). */
    data class StoragePermissionResult(val granted: Boolean) : GalleryIntent

    /** The grid is near its end: load the next page. Ignored while one is loading or at the end. */
    data object LoadMore : GalleryIntent
}

/**
 * Presents the Room history a page at a time. The grid observes the newest N items for the
 * current filter, and [GalleryIntent.LoadMore] raises N by [PAGE_SIZE]. Because that query is
 * live, new creations and deletions show up immediately, which fixed cursor pages would miss, and
 * its total order means a larger window only appends. Counts come from their own query, so they
 * always describe the whole library. Shaping (grouping by day) runs on the Default dispatcher.
 *
 * The open item is observed by id on its own, so it stays correct whatever page or filter is
 * showing, and if it disappears (deleted here or elsewhere) the sheet closes by itself instead of
 * pointing at stale data.
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest: a new filter or window replaces the old query
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val repository: GenerationRepository,
    private val exporter: MediaExporter,
    private val clock: Clock,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) : ViewModel() {

    private val filter = MutableStateFlow(GalleryFilter.ALL)
    private val limit = MutableStateFlow(PAGE_SIZE)
    private val selectedId = MutableStateFlow<String?>(null)
    private val exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    private val _events = Channel<GalleryEvent>(Channel.BUFFERED)

    /** Share requests. A channel, so each is delivered to the screen exactly once. */
    val events: Flow<GalleryEvent> = _events.receiveAsFlow()

    /** The loaded window, tagged with the filter and limit it answers, so stale pages are recognisable. */
    private class Window(val filter: GalleryFilter, val limit: Int, val items: List<MediaItem>)

    private val window: Flow<Window> = combine(filter, limit, ::Pair).flatMapLatest { (filter, limit) ->
        repository.observeLibrary(filter.mediaType, limit).map { Window(filter, limit, it) }
    }

    private val selected: Flow<MediaItem?> = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(null) else repository.observeItem(id)
    }

    val uiState: StateFlow<GalleryUiState> = combine(
        window,
        repository.observeCounts(),
        limit,
        selected,
        exportState,
    ) { window, counts, requestedLimit, selected, export ->
        val complete = window.items.size < window.limit
        GalleryUiState(
            loaded = true,
            filter = window.filter,
            entries = buildGrid(window.items, window.filter, clock.now(), ZoneId.systemDefault(), complete),
            totalCount = counts.total,
            imageCount = counts.images,
            videoCount = counts.videos,
            selected = selected,
            export = export,
            endReached = complete,
            isLoadingMore = requestedLimit > window.limit,
        )
    }
        .flowOn(default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), GalleryUiState())

    fun onIntent(intent: GalleryIntent) {
        when (intent) {
            is GalleryIntent.SetFilter -> setFilter(intent.filter)
            is GalleryIntent.Open -> open(intent.id)
            GalleryIntent.Close -> close()
            is GalleryIntent.Delete -> delete(intent.id)
            GalleryIntent.SaveToGallery -> save()
            is GalleryIntent.Share -> share(intent.chooserTitle)
            is GalleryIntent.StoragePermissionResult -> permissionResult(intent.granted)
            GalleryIntent.LoadMore -> loadMore()
        }
    }

    private fun setFilter(next: GalleryFilter) {
        if (next == filter.value) return
        // A new filter is a different list: start again from its first page.
        limit.value = PAGE_SIZE
        filter.value = next
    }

    private fun loadMore() {
        val state = uiState.value
        // Only grow from the window that is actually on screen, so a burst of requests (or one
        // made while the previous page is still loading) adds exactly one page.
        if (!state.loaded || state.endReached || state.isLoadingMore) return
        if (state.filter != filter.value) return
        limit.value += PAGE_SIZE
    }

    private fun open(id: String) {
        exportState.value = ExportState.Idle
        selectedId.value = id
    }

    private fun close() {
        selectedId.value = null
        exportState.value = ExportState.Idle
    }

    private fun save() {
        val item = uiState.value.selected ?: return
        if (exportState.value == ExportState.Saving) return
        exportState.value = ExportState.Saving
        viewModelScope.launch {
            exportState.value = when (val result = exporter.exportToGallery(item)) {
                is Result.Success -> ExportState.Saved(result.data.folder, result.data.metadataEmbedded)
                is Result.Error -> when (val failure = result.exception) {
                    is VisionStudioException.PermissionRequiredException -> ExportState.NeedsPermission(failure.permission)
                    else -> ExportState.Failed(failure.message ?: "Couldn't save the file")
                }
                is Result.Loading -> ExportState.Saving
            }
        }
    }

    private fun permissionResult(granted: Boolean) {
        if (granted) {
            save()
        } else {
            exportState.value = ExportState.Failed("Saving to your gallery needs storage permission on this version of Android.")
        }
    }

    private fun share(title: CharSequence?) {
        val item = uiState.value.selected ?: return
        viewModelScope.launch {
            when (val result = exporter.createShareIntent(item, title)) {
                is Result.Success -> _events.send(GalleryEvent.Share(result.data))
                is Result.Error -> exportState.value = ExportState.Failed(result.exception.message ?: "Couldn't share the file")
                is Result.Loading -> Unit
            }
        }
    }

    private fun delete(id: String) {
        val item = uiState.value.selected?.takeIf { it.id == id } ?: return
        close()
        viewModelScope.launch { repository.delete(item) }
    }

    companion object {
        /** Items per page. Enough to fill a tall tablet screen twice over in two columns. */
        const val PAGE_SIZE: Int = 40
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
