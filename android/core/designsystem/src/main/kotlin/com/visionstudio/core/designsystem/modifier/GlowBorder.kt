package com.visionstudio.core.designsystem.modifier

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Neon-style border: a crisp core stroke plus [layers] progressively wider, fainter strokes
 * that read as an outer glow. Uses plain strokes (no BlurMaskFilter) so it renders identically
 * on every API level with hardware acceleration.
 *
 * [intensity] is read at draw time, so animating it (e.g. a pulsing "generating" state)
 * only invalidates the draw phase — no recomposition, no cache rebuild, no allocation.
 */
fun Modifier.glowingBorder(
    brush: Brush,
    shape: Shape,
    width: Dp = 1.dp,
    glowRadius: Dp = 10.dp,
    layers: Int = 4,
    intensity: () -> Float = { 1f },
): Modifier {
    require(layers >= 0) { "layers must be >= 0, was $layers" }
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val corePx = width.toPx()
        val glowPx = glowRadius.toPx()
        val coreStroke = Stroke(width = corePx)
        val glowStrokes = Array(layers) { index ->
            val fraction = (index + 1f) / layers
            Stroke(width = corePx + glowPx * 2f * fraction)
        }
        val glowAlphas = FloatArray(layers) { index ->
            GLOW_PEAK_ALPHA * (1f - index.toFloat() / layers)
        }
        onDrawWithContent {
            val level = intensity().coerceIn(0f, 1f)
            if (level > 0f) {
                for (index in layers - 1 downTo 0) {
                    drawOutline(outline, brush = brush, alpha = glowAlphas[index] * level, style = glowStrokes[index])
                }
            }
            drawContent()
            drawOutline(outline, brush = brush, style = coreStroke)
        }
    }
}

private const val GLOW_PEAK_ALPHA = 0.22f
