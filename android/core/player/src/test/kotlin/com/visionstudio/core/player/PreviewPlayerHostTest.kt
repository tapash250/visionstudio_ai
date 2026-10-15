package com.visionstudio.core.player

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewPlayerHostTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var host: PreviewPlayerHost

    @Before fun setUp() { host = PreviewPlayerHost(context) }
    @After fun tearDown() = host.release()

    @Test
    fun `the shared player loops silently and is wired to the shared view`() {
        assertEquals(Player.REPEAT_MODE_ONE, host.player.repeatMode)
        assertEquals(0f, host.player.volume, 0f)
        assertSame(host.player, host.view.player)
    }

    @Test
    fun `the view moves between tiles instead of being recreated`() {
        val first = FrameLayout(context)
        val second = FrameLayout(context)
        val originalView = host.view

        host.attachTo(first)
        assertSame(first, host.view.parent)
        assertEquals(1, first.childCount)

        host.attachTo(second)
        assertSame("same view instance is re-parented", originalView, host.view)
        assertSame(second, host.view.parent)
        assertEquals("old tile no longer holds it", 0, first.childCount)
        assertEquals(1, second.childCount)
    }

    @Test
    fun `attaching to the container it is already in does nothing`() {
        val tile = FrameLayout(context)
        host.attachTo(tile)

        host.attachTo(tile)

        assertEquals(1, tile.childCount)
    }

    @Test
    fun `a stale release of the old tile cannot steal the surface back`() {
        val old = FrameLayout(context)
        val next = FrameLayout(context)
        host.attachTo(old)
        host.attachTo(next) // the new active tile composes first...

        host.detachFrom(old) // ...then the old tile is disposed

        assertSame("the surface stays with the new tile", next, host.view.parent)
        assertEquals(1, next.childCount)
    }

    @Test
    fun `detaching from the current tile frees the surface`() {
        val tile = FrameLayout(context)
        host.attachTo(tile)

        host.detachFrom(tile)

        assertNull(host.view.parent)
    }

    @Test
    fun `switching clips hides the stale frame until the new clip draws`() {
        host.attachTo(FrameLayout(context))
        host.play("file:///a.mp4")
        host.frameListener.onRenderedFirstFrame()
        assertEquals(View.VISIBLE, host.view.videoSurfaceView?.visibility)

        host.play("file:///b.mp4")

        assertEquals("file:///b.mp4", host.currentUri)
        assertEquals("old frame is hidden during the swap", View.INVISIBLE, host.view.videoSurfaceView?.visibility)

        host.frameListener.onRenderedFirstFrame()
        assertEquals(View.VISIBLE, host.view.videoSurfaceView?.visibility)
    }

    @Test
    fun `replaying the loaded clip resumes without reloading`() {
        host.play("file:///a.mp4")
        host.frameListener.onRenderedFirstFrame()
        host.pause()

        host.play("file:///a.mp4")

        assertEquals("not hidden again: nothing was swapped", View.VISIBLE, host.view.videoSurfaceView?.visibility)
        assertEquals(1, host.player.mediaItemCount)
    }

    @Test
    fun `clear forgets the clip`() {
        host.play("file:///a.mp4")

        host.clear()

        assertNull(host.currentUri)
        assertEquals(0, host.player.mediaItemCount)
    }

    @Test
    fun `release is idempotent, detaches the view and stops further use`() {
        val tile = FrameLayout(context)
        host.attachTo(tile)

        host.release()
        host.release()

        assertTrue(host.isReleased)
        assertNull(host.view.parent)
        assertNull(host.view.player)
        host.pause()
        host.clear()
        assertFalse(runCatching { host.play("file:///a.mp4") }.isSuccess)
    }
}
