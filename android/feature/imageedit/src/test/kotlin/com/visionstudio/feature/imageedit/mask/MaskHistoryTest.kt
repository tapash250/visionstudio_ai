package com.visionstudio.feature.imageedit.mask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskHistoryTest {

    private fun stroke(tag: Float) = MaskStroke(floatArrayOf(tag, tag), 0.1f, erase = false)

    private fun MaskHistory.tags() = strokes.map { it.x(0) }

    @Test
    fun `starts empty with nothing to undo or redo`() {
        val history = MaskHistory.Empty
        assertTrue(history.isEmpty)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `commit appends and undo redo walk the stack`() {
        val history = MaskHistory.Empty.commit(stroke(0.1f)).commit(stroke(0.2f)).commit(stroke(0.3f))
        assertEquals(listOf(0.1f, 0.2f, 0.3f), history.tags())

        val undone = history.undo().undo()
        assertEquals(listOf(0.1f), undone.tags())
        assertTrue(undone.canRedo)

        val redone = undone.redo()
        assertEquals(listOf(0.1f, 0.2f), redone.tags())
        assertEquals(listOf(0.1f, 0.2f, 0.3f), redone.redo().tags())
        assertFalse(redone.redo().canRedo)
    }

    @Test
    fun `committing after undo discards the redo branch`() {
        val history = MaskHistory.Empty.commit(stroke(0.1f)).commit(stroke(0.2f)).undo()
        assertTrue(history.canRedo)

        val branched = history.commit(stroke(0.9f))

        assertEquals(listOf(0.1f, 0.9f), branched.tags())
        assertFalse(branched.canRedo)
    }

    @Test
    fun `undo and redo on empty are no-ops that return the same instance`() {
        assertSame(MaskHistory.Empty, MaskHistory.Empty.undo())
        assertSame(MaskHistory.Empty, MaskHistory.Empty.redo())
    }

    @Test
    fun `history is immutable - old snapshots are unaffected`() {
        val one = MaskHistory.Empty.commit(stroke(0.1f))
        val two = one.commit(stroke(0.2f))
        one.undo()
        assertEquals(listOf(0.1f), one.tags())
        assertEquals(listOf(0.1f, 0.2f), two.tags())
    }

    @Test
    fun `clear wipes strokes and redo`() {
        val cleared = MaskHistory.Empty.commit(stroke(0.1f)).commit(stroke(0.2f)).undo().clear()
        assertTrue(cleared.isEmpty)
        assertFalse(cleared.canRedo)
    }

    @Test
    fun `stroke copies its input and validates`() {
        val points = floatArrayOf(0.1f, 0.2f)
        val stroke = MaskStroke(points, 0.1f, false)
        points[0] = 0.9f
        assertEquals(0.1f, stroke.x(0), 0f)

        assertTrue(runCatching { MaskStroke(floatArrayOf(), 0.1f, false) }.isFailure)
        assertTrue(runCatching { MaskStroke(floatArrayOf(0.1f), 0.1f, false) }.isFailure)
        assertTrue(runCatching { MaskStroke(floatArrayOf(0.1f, 0.1f), 0f, false) }.isFailure)
        assertTrue(runCatching { MaskStroke(floatArrayOf(0.1f, 0.1f), 1.5f, false) }.isFailure)
    }
}
