package com.visionstudio.feature.imageedit.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.haptics.rememberStudioHaptics
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.feature.imageedit.mask.BrushSettings
import com.visionstudio.feature.imageedit.mask.MaskHistory
import com.visionstudio.feature.imageedit.mask.MaskStroke
import com.visionstudio.feature.imageedit.mask.StrokeInk

/**
 * Touch canvas for painting an inpainting mask over a photo.
 *
 * Layers, bottom to top: the photo (a hardware bitmap), the mask overlay, and the brush cursor.
 *
 * - The overlay renders into an offscreen layer with opaque strokes and a uniform layer alpha,
 *   so overlapping strokes never compound into darker patches, and eraser strokes use
 *   [BlendMode.Clear] to cut real holes in earlier paint.
 * - Committed stroke [Path]s and [Stroke] styles are built in `drawWithCache` and only rebuilt
 *   when the canvas size, history or brush changes. The stroke under the finger is a single
 *   reusable [StrokeInk]; its path grows in place and only its version counter is observed,
 *   so painting invalidates the draw pass and never recomposes.
 *
 * Coordinates are normalised on commit, so the same strokes rasterise at any resolution.
 */
@Composable
fun InpaintMaskCanvas(
    source: ImageBitmap,
    history: MaskHistory,
    brush: BrushSettings,
    onStrokeCommitted: (MaskStroke) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = "",
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val ink = remember { StrokeInk() }
    val haptics = rememberStudioHaptics()
    val currentBrush by rememberUpdatedState(brush)
    val currentCommit by rememberUpdatedState(onStrokeCommitted)

    // Once the committed stroke is part of the drawn history, drop the duplicate live ink.
    LaunchedEffect(history) { ink.release() }

    Box(
        modifier = modifier
            .aspectRatio(source.width.toFloat() / source.height.toFloat())
            .clip(StudioShapes.Card)
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    ink.begin(down.position.x, down.position.y, size.width.toFloat(), size.height.toFloat())
                    haptics.gestureStart()
                    var endX = down.position.x
                    var endY = down.position.y
                    try {
                        var lifted = false
                        while (!lifted) {
                            val event = awaitPointerEvent()
                            var tracked: PointerInputChange? = null
                            for (change in event.changes) {
                                if (change.id == down.id) {
                                    tracked = change
                                    break
                                }
                            }
                            if (tracked == null) {
                                ink.cancel()
                                return@awaitEachGesture
                            }
                            endX = tracked.position.x
                            endY = tracked.position.y
                            if (!tracked.pressed) {
                                lifted = true
                                tracked.consume()
                            } else if (tracked.positionChanged()) {
                                ink.append(endX, endY)
                                tracked.consume()
                            }
                        }
                        val stroke = ink.finish(endX, endY, currentBrush)
                        haptics.gestureEnd()
                        currentCommit(stroke)
                    } finally {
                        // Cancelled mid-gesture (screen disabled, composition left): drop the half stroke.
                        if (ink.active) ink.cancel()
                    }
                }
            },
    ) {
        Image(
            bitmap = source,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )

        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = OVERLAY_ALPHA
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithCache {
                    val width = size.width
                    val height = size.height
                    val strokes = history.strokes
                    val paths = Array(strokes.size) { i -> Path().also { strokes[i].appendTo(it, width, height) } }
                    val styles = Array(strokes.size) { i ->
                        Stroke(width = strokes[i].diameterFraction * width, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    }
                    val liveStyle = Stroke(width = brush.diameterFraction * width, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val liveBlend = if (brush.erase) BlendMode.Clear else BlendMode.SrcOver
                    onDrawBehind {
                        val version = ink.version // observed: each new sample redraws this layer
                        for (i in paths.indices) {
                            drawPath(
                                path = paths[i],
                                color = MASK_COLOR,
                                style = styles[i],
                                blendMode = if (strokes[i].erase) BlendMode.Clear else BlendMode.SrcOver,
                            )
                        }
                        if (version >= 0 && (ink.active || !ink.path.isEmpty)) {
                            drawPath(ink.path, MASK_COLOR, style = liveStyle, blendMode = liveBlend)
                        }
                    }
                },
        )

        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val radius = brush.diameterFraction * size.width / 2f
                    val outer = Stroke(width = 3.dp.toPx())
                    val inner = Stroke(width = 1.5.dp.toPx())
                    onDrawBehind {
                        val version = ink.version
                        if (version >= 0 && ink.active) {
                            drawCircle(RING_SHADOW, radius, Offset(ink.cursorX, ink.cursorY), style = outer)
                            drawCircle(RING_LIGHT, radius, Offset(ink.cursorX, ink.cursorY), style = inner)
                        }
                    }
                },
        )

        overlay()
    }
}

/** Violet-magenta: reads clearly on skin, sky, foliage and shadow alike, and never collides with the prism cyan UI. */
private val MASK_COLOR = Color(0xFFFF2DA6)
private val RING_LIGHT = Color.White
private val RING_SHADOW = Color.Black.copy(alpha = 0.55f)
private const val OVERLAY_ALPHA = 0.58f
