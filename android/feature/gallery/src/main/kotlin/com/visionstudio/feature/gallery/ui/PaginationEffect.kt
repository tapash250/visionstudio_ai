package com.visionstudio.feature.gallery.ui

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/**
 * Calls [onLoadMore] when the last visible item comes within [prefetchDistance] items of the end.
 *
 * The scroll position is read only inside [snapshotFlow], on the effect's coroutine, so scrolling
 * never invalidates the calling composable: no recomposition per frame.
 *
 * It emits the item count that crossed the threshold rather than a bare "near the end" boolean.
 * A boolean that stays true after a page arrives (a short page on a tall screen still leaves the
 * end in view) would never change again and loading would stall; a new count is a new value, so
 * the next page is requested. Nulls (not near the end, or [enabled] false) also pass through
 * [distinctUntilChanged], so becoming eligible again at the same count fires again. [onLoadMore]
 * must therefore be idempotent; the Vault's ViewModel ignores requests while a page is loading.
 *
 * [resetKey] restarts observation from scratch, for when the list is replaced (a new filter) and a
 * count could repeat one seen before.
 */
@Composable
internal fun LazyStaggeredGridPaginationEffect(
    state: LazyStaggeredGridState,
    onLoadMore: () -> Unit,
    enabled: Boolean = true,
    prefetchDistance: Int = DEFAULT_PREFETCH_DISTANCE,
    resetKey: Any? = null,
) {
    require(prefetchDistance >= 0) { "prefetchDistance must not be negative" }
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    val currentEnabled by rememberUpdatedState(enabled)

    LaunchedEffect(state, prefetchDistance, resetKey) {
        snapshotFlow {
            if (!currentEnabled) return@snapshotFlow null
            val info = state.layoutInfo
            val total = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.maxOfOrNull { it.index } ?: return@snapshotFlow null
            if (total > 0 && lastVisible >= total - 1 - prefetchDistance) total else null
        }
            .distinctUntilChanged()
            .filterNotNull()
            .collect { currentOnLoadMore() }
    }
}

/** About a screen's worth of tiles in two columns, so the next page is ready before it's needed. */
internal const val DEFAULT_PREFETCH_DISTANCE: Int = 12
