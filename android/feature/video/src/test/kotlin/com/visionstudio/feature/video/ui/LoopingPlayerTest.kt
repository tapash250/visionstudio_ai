package com.visionstudio.feature.video.ui

import android.app.Application
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoopingPlayerTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a new preview player loops one clip forever, plays on ready, and is silent`() {
        val player = createLoopingPlayer(context)
        try {
            assertEquals("loops the single clip", Player.REPEAT_MODE_ONE, player.repeatMode)
            assertTrue("starts as soon as ready", player.playWhenReady)
            assertEquals("muted autoplay", 0f, player.volume, 0f)
        } finally {
            player.release()
        }
    }

    @Test
    fun `the looping policy can be applied to any player`() {
        val player = createLoopingPlayer(context)
        try {
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.playWhenReady = false
            player.volume = 1f

            configureLoopingPreview(player)

            assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
            assertTrue(player.playWhenReady)
            assertEquals(0f, player.volume, 0f)
        } finally {
            player.release()
        }
    }
}
