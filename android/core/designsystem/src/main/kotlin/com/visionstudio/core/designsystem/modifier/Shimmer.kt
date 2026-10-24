package com.visionstudio.core.designsystem.modifier

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import com.visionstudio.core.designsystem.theme.StudioCyan

/**
 * A diagonal light band sweeping over the content, marking "something is forming here".
 * The band brush is cached per size and moved with translate, so nothing is allocated per frame.
 */
fun Modifier.shimmer(
    enabled: Boolean = true,
    highlight: Color = StudioCyan.copy(alpha = 0.16f),
    durationMillis: Int = SWEEP_MILLIS,
): Modifier = composed {
    if (!enabled) return@composed Modifier
    val travel = rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing)),
        label = "shimmerTravel",
    )
    drawWithCache {
        val band = size.maxDimension * BAND_FRACTION
        val brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, highlight, Color.White.copy(alpha = highlight.alpha * 0.8f), highlight, Color.Transparent),
            startX = 0f,
            endX = band,
        )
        // Tall enough to cover the rotated content box.
        val bandSize = Size(band, size.maxDimension * 2f)
        val pivot = Offset(size.width / 2f, size.height / 2f)
        onDrawWithContent {
            drawContent()
            rotate(BAND_ANGLE, pivot) {
                translate(left = size.width * travel.value - band / 2f, top = -size.maxDimension / 2f) {
                    drawRect(brush, size = bandSize)
                }
            }
        }
    }
}

private const val SWEEP_MILLIS = 1_600
private const val BAND_FRACTION = 0.45f
private const val BAND_ANGLE = 18f
