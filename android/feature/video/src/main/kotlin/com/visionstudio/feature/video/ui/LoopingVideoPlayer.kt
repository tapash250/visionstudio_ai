package com.visionstudio.feature.video.ui

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.tactileClickable

/**
 * Applies the looping-preview policy to [player]: repeat the one clip forever, start playing as
 * soon as it is ready, and stay silent. Generated clips usually have no soundtrack, and a
 * muted loop can autoplay without surprising the user.
 */
fun configureLoopingPreview(player: Player) {
    player.repeatMode = Player.REPEAT_MODE_ONE
    player.playWhenReady = true
    player.volume = 0f
}

/**
 * Builds an ExoPlayer for short local clips. Media3 marks the scaling-mode API as unstable
 * (its signature may change between minor releases); the opt-in is deliberate and confined here.
 */
@OptIn(UnstableApi::class)
fun createLoopingPlayer(context: Context): ExoPlayer =
    ExoPlayer.Builder(context.applicationContext)
        .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        .build()
        .also(::configureLoopingPreview)

/**
 * Plays [uri] in a seamless silent loop on a hardware `SurfaceView`, which decodes straight to
 * the display compositor with no per-frame copy through the UI thread. Tap to pause or resume.
 *
 * The player belongs to this composition: it pauses when the app leaves the foreground, resumes
 * when it returns (unless the user paused it), and is released on dispose.
 */
@OptIn(UnstableApi::class) // PlayerView resize/shutter/keep-content options are @UnstableApi in Media3 1.4
@Composable
fun LoopingVideoPlayer(
    uri: String,
    description: String,
    modifier: Modifier = Modifier,
    cornerRadiusPx: Float = 0f,
) {
    val context = LocalContext.current
    val player = remember { createLoopingPlayer(context) }
    var userPaused by remember { mutableStateOf(false) }

    DisposableEffect(player) { onDispose { player.release() } }

    // Swap the clip without tearing the player (and its surface) down.
    LaunchedEffect(uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        userPaused = false
        player.playWhenReady = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { if (!userPaused) player.play() }

    Box(
        modifier = modifier
            .semantics { contentDescription = description }
            .tactileClickable(haptic = HapticPattern.Tick, onClickLabel = null) {
                userPaused = !userPaused
                if (userPaused) player.pause() else player.play()
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                    if (cornerRadiusPx > 0f) {
                        outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: Outline) {
                                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                            }
                        }
                        clipToOutline = true
                    }
                }
            },
            onRelease = { view -> view.player = null },
        )
    }
}
