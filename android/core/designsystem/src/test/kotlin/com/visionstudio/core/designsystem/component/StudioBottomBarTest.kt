package com.visionstudio.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StudioBottomBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val items = listOf(
        BottomBarItem("Generate", Icons.Rounded.AutoAwesome),
        BottomBarItem("Video", Icons.Rounded.Movie),
        BottomBarItem("Settings", Icons.Rounded.Tune),
    )

    @Test
    fun `tapping another item reports its index`() {
        val selections = mutableListOf<Int>()
        compose.setContent { StudioBottomBar(items, selectedIndex = 0, onSelect = selections::add) }

        compose.onNodeWithText("Settings").performClick()

        assertEquals(listOf(2), selections)
    }

    @Test
    fun `tapping the selected item does nothing`() {
        val selections = mutableListOf<Int>()
        compose.setContent { StudioBottomBar(items, selectedIndex = 1, onSelect = selections::add) }

        compose.onNodeWithText("Video").performClick()

        assertEquals(emptyList<Int>(), selections)
    }

    @Test
    fun `selection follows the state the caller owns`() {
        var selected by mutableIntStateOf(0)
        compose.setContent { StudioBottomBar(items, selectedIndex = selected, onSelect = { selected = it }) }
        compose.onNodeWithText("Generate").assertIsSelected()

        compose.onNodeWithText("Video").performClick()

        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithText("Video").assertIsSelected()
    }

    @Test
    fun `an out of range selection does not crash`() {
        compose.setContent { StudioBottomBar(items, selectedIndex = 9, onSelect = {}) }

        compose.onNodeWithText("Settings").assertExists()
    }

    @Test
    fun `an empty bar is rejected`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            compose.setContent { StudioBottomBar(emptyList(), selectedIndex = 0, onSelect = {}) }
        }

        assertEquals("StudioBottomBar needs at least one item", failure.message)
    }
}
