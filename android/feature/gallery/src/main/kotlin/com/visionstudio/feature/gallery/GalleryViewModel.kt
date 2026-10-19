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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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
}

/**
 * Presents the whole Room history. The list is shaped (filtered, sorted, grouped by day) on the
 * Default dispatcher, off the main thread. The open item is tracked by id and resolved against the
 * live list, so if it disappears (deleted here or elsewhere) the sheet closes by itself instead of
 * pointing at stale data.
 */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val repository: GenerationRepository,
    private val exporter: MediaExporter,
    private val clock: Clock,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) : ViewModel() {

    private val filter = MutableStateFlow(GalleryFilter.ALL)
    private val selectedId = MutableStateFlow<String?>(null)
    private val exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    private val _events = Channel<GalleryEvent>(Channel.BUFFERED)

    /** Share requests. A channel, so each is delivered to the screen exactly once. */
    val events: Flow<GalleryEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<GalleryUiState> = combine(repository.observeAll(), filter, selectedId, exportState) { items, filter, selectedId, export ->
        GalleryUiState(
            loaded = true,
            filter = filter,
            entries = buildGrid(items, filter, clock.now(), ZoneId.systemDefault()),
            totalCount = items.size,
            imageCount = items.count { GalleryFilter.IMAGES.accepts(it) },
            videoCount = items.count { GalleryFilter.VIDEOS.accepts(it) },
            selected = selectedId?.let { id -> items.firstOrNull { it.id == id } },
            export = export,
        )
    }
        .flowOn(default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), GalleryUiState())

    fun onIntent(intent: GalleryIntent) {
        when (intent) {
            is GalleryIntent.SetFilter -> filter.value = intent.filter
            is GalleryIntent.Open -> open(intent.id)
            GalleryIntent.Close -> close()
            is GalleryIntent.Delete -> delete(intent.id)
            GalleryIntent.SaveToGallery -> save()
            is GalleryIntent.Share -> share(intent.chooserTitle)
            is GalleryIntent.StoragePermissionResult -> permissionResult(intent.granted)
        }
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

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
