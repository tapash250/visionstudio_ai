package com.visionstudio.feature.gallery.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaginationEffectTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = LazyStaggeredGridState()
    private var itemCount by mutableIntStateOf(20)
    private var enabled by mutableStateOf(true)
    private var resetKey by mutableStateOf<Any?>("first")
    private var loadMoreCalls = 0
    private var parentCompositions = 0

    /** Two 100dp columns in a 200dp-tall viewport: two rows (four tiles) visible at a time. */
    private fun setGrid() = compose.setContent {
        parentCompositions++
        LazyStaggeredGridPaginationEffect(
            state = state,
            onLoadMore = { loadMoreCalls++ },
            enabled = enabled,
            prefetchDistance = 4,
            resetKey = resetKey,
        )
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            state = state,
            modifier = Modifier.width(200.dp).height(200.dp),
        ) {
            items(count = itemCount, key = { it }) { Box(Modifier.height(100.dp)) }
        }
    }

    private fun scrollTo(index: Int) {
        compose.runOnIdle { runBlocking { state.scrollToItem(index) } }
        compose.waitForIdle()
    }

    @Test
    fun `fires once per page as the end comes into reach, without recomposing on scroll`() {
        setGrid()
        compose.waitForIdle()
        assertEquals(0, loadMoreCalls)

        scrollTo(6) // last visible about 9: not yet within 4 of the end (index 15)
        assertEquals(0, loadMoreCalls)

        scrollTo(14) // last visible about 17: within reach
        assertEquals(1, loadMoreCalls)

        scrollTo(16) // still the same 20-item page: no second request
        assertEquals(1, loadMoreCalls)
        assertEquals("scrolling never recomposed the host", 1, parentCompositions)

        itemCount = 40 // the next page arrives
        compose.waitForIdle()
        scrollTo(34)
        assertEquals(2, loadMoreCalls)
    }

    @Test
    fun `a page that still leaves the end in view requests the next one`() {
        itemCount = 2 // shorter than the viewport: the end is visible immediately
        setGrid()
        compose.waitForIdle()
        assertEquals(1, loadMoreCalls)

        itemCount = 4 // still short: a "near the end" boolean would stay true and never fire again
        compose.waitForIdle()
        assertEquals(2, loadMoreCalls)
    }

    @Test
    fun `disabled while a page loads, then fires again once re-enabled`() {
        itemCount = 2
        enabled = false
        setGrid()
        compose.waitForIdle()
        assertEquals(0, loadMoreCalls)

        enabled = true
        compose.waitForIdle()
        assertEquals(1, loadMoreCalls)

        // The load found nothing new (same count), but loading finished: asking again is allowed.
        enabled = false
        compose.waitForIdle()
        enabled = true
        compose.waitForIdle()
        assertEquals(2, loadMoreCalls)
    }

    @Test
    fun `a new list with a reset key starts over even at a count seen before`() {
        itemCount = 2
        setGrid()
        compose.waitForIdle()
        assertEquals(1, loadMoreCalls)

        resetKey = "second"
        compose.waitForIdle()
        assertEquals(2, loadMoreCalls)
    }
}
