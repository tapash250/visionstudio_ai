package com.visionstudio.core.player

import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Creates the one shared [PreviewPlayerHost] for a screen. It pauses when the app leaves the
 * foreground and is released when the screen leaves composition.
 */
@Composable
fun rememberPreviewPlayerHost(cornerRadiusPx: Float = 0f): PreviewPlayerHost {
    val context = LocalContext.current
    val host = remember(cornerRadiusPx) { PreviewPlayerHost(context, cornerRadiusPx) }
    DisposableEffect(host) { onDispose { host.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { host.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { if (host.currentUri != null) host.player.play() }
    return host
}

/**
 * Hosts [host]'s shared surface inside this composable. Compose only this for the *active*
 * tile; when the active tile changes, the surface is re-parented rather than recreated.
 *
 * Draw the tile's poster *before* this in the same box: a `SurfaceView` punches a hole through
 * whatever was drawn beneath it, so the video shows through the poster once frames arrive.
 */
@Composable
fun PreviewSurface(host: PreviewPlayerHost, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context -> FrameLayout(context).also(host::attachTo) },
        onRelease = host::detachFrom,
    )
}
