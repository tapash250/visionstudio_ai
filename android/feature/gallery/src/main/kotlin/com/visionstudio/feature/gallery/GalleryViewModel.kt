package com.visionstudio.feature.gallery

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visionstudio.core.common.dispatchers.DefaultDispatcher
import com.visionstudio.core.data.generation.GenerationRepository
import com.visionstudio.core.data.video.Clock
import com.visionstudio.core.model.MediaItem
import com.visionstudio.feature.gallery.model.GalleryFilter
import com.visionstudio.feature.gallery.model.GridEntry
import com.visionstudio.feature.gallery.model.buildGrid
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

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
) {
    val isLibraryEmpty: Boolean get() = loaded && totalCount == 0
    val isFilterEmpty: Boolean get() = loaded && totalCount > 0 && entries.isEmpty()
}

sealed interface GalleryIntent {
    data class SetFilter(val filter: GalleryFilter) : GalleryIntent
    data class Open(val id: String) : GalleryIntent
    data object Close : GalleryIntent
    data class Delete(val id: String) : GalleryIntent
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
    private val clock: Clock,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) : ViewModel() {

    private val filter = MutableStateFlow(GalleryFilter.ALL)
    private val selectedId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<GalleryUiState> = combine(repository.observeAll(), filter, selectedId) { items, filter, selectedId ->
        GalleryUiState(
            loaded = true,
            filter = filter,
            entries = buildGrid(items, filter, clock.now(), ZoneId.systemDefault()),
            totalCount = items.size,
            imageCount = items.count { GalleryFilter.IMAGES.accepts(it) },
            videoCount = items.count { GalleryFilter.VIDEOS.accepts(it) },
            selected = selectedId?.let { id -> items.firstOrNull { it.id == id } },
        )
    }
        .flowOn(default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), GalleryUiState())

    fun onIntent(intent: GalleryIntent) {
        when (intent) {
            is GalleryIntent.SetFilter -> filter.value = intent.filter
            is GalleryIntent.Open -> selectedId.value = intent.id
            GalleryIntent.Close -> selectedId.value = null
            is GalleryIntent.Delete -> delete(intent.id)
        }
    }

    private fun delete(id: String) {
        val item = uiState.value.selected?.takeIf { it.id == id } ?: return
        selectedId.update { null }
        viewModelScope.launch { repository.delete(item) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
