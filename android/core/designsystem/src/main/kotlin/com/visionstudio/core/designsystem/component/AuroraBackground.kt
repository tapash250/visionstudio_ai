package com.visionstudio.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioIndigo
import com.visionstudio.core.designsystem.theme.StudioOnyx
import com.visionstudio.core.designsystem.theme.StudioViolet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The signature canvas: Obsidian base with three slowly orbiting light pools (indigo, violet,
 * cyan) under a soft vignette, like light refracting through dark glass.
 *
 * Zero-allocation animation: the radial brushes are built once per size, centred on the
 * origin, and moved with `translate`. The phase is read only in the draw lambda, so motion
 * invalidates the draw pass and never recomposes.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val phase = if (animated) {
        rememberInfiniteTransition(label = "aurora").animateFloat(
            initialValue = 0f,
            targetValue = TWO_PI,
            animationSpec = infiniteRepeatable(tween(CYCLE_MILLIS, easing = LinearEasing)),
            label = "auroraPhase",
        )
    } else {
        null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val radius = size.maxDimension * POOL_RADIUS
                val indigoPool = Brush.radialGradient(
                    colors = listOf(StudioIndigo.copy(alpha = 0.34f), StudioIndigo.copy(alpha = 0.08f), Color.Transparent),
                    center = Offset.Zero,
                    radius = radius,
                )
                val violetPool = Brush.radialGradient(
                    colors = listOf(StudioViolet.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset.Zero,
                    radius = radius * 0.8f,
                )
                val cyanPool = Brush.radialGradient(
                    colors = listOf(StudioCyan.copy(alpha = 0.18f), Color.Transparent),
                    center = Offset.Zero,
                    radius = radius * 0.9f,
                )
                val vignette = Brush.radialGradient(
                    colors = listOf(Color.Transparent, StudioOnyx.copy(alpha = 0.85f)),
                    center = Offset(size.width / 2f, size.height * 0.4f),
                    radius = size.maxDimension * 0.85f,
                )
                val w = size.width
                val h = size.height
                onDrawBehind {
                    val t = phase?.value ?: 0f
                    drawRect(StudioOnyx)
                    translate(w * (0.18f + 0.14f * cos(t)), h * (0.10f + 0.07f * sin(t))) {
                        drawCircle(indigoPool, radius, Offset.Zero)
                    }
                    translate(w * (0.92f + 0.10f * sin(t * 2f + 1f)), h * (0.42f + 0.10f * cos(t))) {
                        drawCircle(violetPool, radius * 0.8f, Offset.Zero)
                    }
                    translate(w * (0.30f + 0.18f * sin(t + 2f)), h * (0.95f + 0.04f * cos(t * 3f))) {
                        drawCircle(cyanPool, radius * 0.9f, Offset.Zero)
                    }
                    drawRect(vignette)
                }
            },
        content = content,
    )
}

private const val TWO_PI = (2 * PI).toFloat()
private const val CYCLE_MILLIS = 32_000
private const val POOL_RADIUS = 0.62f
