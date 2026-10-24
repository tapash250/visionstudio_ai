package com.visionstudio.core.player

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * One ExoPlayer and one `SurfaceView`, shared by every inline video preview in a grid.
 *
 * Decoders and surfaces are the scarce resource on a phone: a player per tile would fail long
 * before a Vault of fifty clips scrolled past. Instead, exactly one tile at a time is *active*;
 * the host's [view] is moved into that tile's container ([attachTo]) and fed that tile's clip
 * ([play]). The expensive pieces (the player, its decoder, the `SurfaceView` and its surface)
 * are created once and reused, so handing the preview from tile to tile is a re-parent and a
 * media-item swap, not a teardown.
 *
 * A stale frame from the previous clip is hidden until the new clip renders its first frame, so
 * the tile's poster stays visible during the swap instead of flashing the old video.
 *
 * Main-thread only, like every Media3 player.
 */
@OptIn(UnstableApi::class)
class PreviewPlayerHost(context: Context, cornerRadiusPx: Float = 0f) {

    val player: ExoPlayer = createLoopingPlayer(context)

    val view: PlayerView = PlayerView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        setShutterBackgroundColor(Color.TRANSPARENT)
        setKeepContentOnPlayerReset(false)
        player = this@PreviewPlayerHost.player
        if (cornerRadiusPx > 0f) {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            clipToOutline = true
        }
    }

    /** The clip currently loaded, or null when idle. */
    var currentUri: String? = null
        private set

    var isReleased: Boolean = false
        private set

    /** Revealed once the new clip has drawn a frame; until then the tile's poster shows through. */
    internal val frameListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            view.videoSurfaceView?.visibility = View.VISIBLE
        }
    }

    init {
        player.addListener(frameListener)
    }

    /** Starts [uri]. Re-selecting the clip that is already loaded just resumes it. */
    fun play(uri: String) {
        check(!isReleased) { "PreviewPlayerHost was released" }
        if (uri != currentUri) {
            view.videoSurfaceView?.visibility = View.INVISIBLE
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            currentUri = uri
        }
        player.play()
    }

    fun pause() {
        if (!isReleased) player.pause()
    }

    /** Stops and forgets the clip, so no tile can show a stale frame. */
    fun clear() {
        if (isReleased) return
        player.stop()
        player.clearMediaItems()
        currentUri = null
    }

    /**
     * Moves the shared surface into [container]. Safe to call whether the view is currently
     * unattached or inside another tile's container, and in either order relative to the old
     * tile's [detachFrom].
     */
    fun attachTo(container: FrameLayout) {
        val parent = view.parent as? ViewGroup
        if (parent === container) return
        parent?.removeView(view)
        container.addView(view)
    }

    /** Releases [container]'s claim. A no-op if the surface has already moved to another tile. */
    fun detachFrom(container: ViewGroup) {
        if (view.parent === container) container.removeView(view)
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        (view.parent as? ViewGroup)?.removeView(view)
        player.removeListener(frameListener)
        view.player = null
        player.release()
        currentUri = null
    }
}
