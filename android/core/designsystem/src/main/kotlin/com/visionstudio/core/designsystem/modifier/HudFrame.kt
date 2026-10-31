package com.visionstudio.core.designsystem.modifier

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.theme.NeonBrushes
import com.visionstudio.core.designsystem.theme.StudioShapes

/**
 * The neon HUD frame used by every card, dock and control: an outer glow, a navy glass fill
 * with a faint top sheen, a fine inner rule inset from the edge, the gradient frame stroke and
 * two short bright accents centred on the top and bottom edges.
 *
 * With [clipContent] the content is clipped to [shape]; the glow and frame are always drawn
 * outside it. Containers whose children stay inside their bounds (docks, tab tracks) can skip the
 * clip, which also keeps touch hit-testing to the plain bounds.
 * [glow] is read at draw time, so animating it only invalidates drawing.
 */
fun Modifier.hudFrame(
    shape: Shape = StudioShapes.Card,
    border: Brush = NeonBrushes.Frame,
    fill: Brush = NeonBrushes.PanelFill,
    borderWidth: Dp = 1.4.dp,
    glowRadius: Dp = 9.dp,
    innerRule: Boolean = true,
    accents: Boolean = true,
    clipContent: Boolean = true,
    glow: () -> Float = { DEFAULT_GLOW },
): Modifier = this
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val inset = INNER_INSET.dp.toPx()
        val innerSize = Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f))
        val inner = shape.createOutline(innerSize, layoutDirection, this)
        val frame = Stroke(width = borderWidth.toPx())
        val hairline = Stroke(width = 0.8.dp.toPx())
        val glowPx = glowRadius.toPx()
        val glowStrokes = Array(GLOW_LAYERS) { i -> Stroke(width = frame.width + glowPx * 2f * (i + 1f) / GLOW_LAYERS) }
        val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Transparent), endY = size.height * 0.5f)
        val accentLength = size.width * ACCENT_FRACTION
        val accentStroke = 2.4.dp.toPx()
        val accentBrush = Brush.horizontalGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.9f), Color.Transparent),
            startX = (size.width - accentLength) / 2f,
            endX = (size.width + accentLength) / 2f,
        )
        onDrawWithContent {
            val level = glow().coerceIn(0f, 1f)
            if (level > 0f) {
                for (i in GLOW_LAYERS - 1 downTo 0) {
                    drawOutline(outline, border, alpha = GLOW_PEAK * level * (1f - i.toFloat() / GLOW_LAYERS), style = glowStrokes[i])
                }
            }
            drawOutline(outline, fill)
            drawOutline(outline, sheen)
            if (innerRule && innerSize.minDimension > 0f) {
                translate(inset, inset) { drawOutline(inner, border, alpha = 0.22f, style = hairline) }
            }
            drawContent()
            drawOutline(outline, border, style = frame)
            if (accents && size.width > MIN_ACCENT_WIDTH.dp.toPx()) {
                val start = (size.width - accentLength) / 2f
                drawLine(accentBrush, Offset(start, 0f), Offset(start + accentLength, 0f), accentStroke, StrokeCap.Round, alpha = 0.55f)
                drawLine(
                    accentBrush,
                    Offset(start, size.height),
                    Offset(start + accentLength, size.height),
                    accentStroke,
                    StrokeCap.Round,
                    alpha = 0.55f,
                )
            }
        }
    }
    .then(if (clipContent) Modifier.clip(shape) else Modifier)

/**
 * Paints this content (typically an [androidx.compose.material3.Icon] tinted white) with
 * [brush], keeping the content's alpha as the mask.
 */
fun Modifier.gradientTint(brush: Brush): Modifier = drawWithCache {
    val layerPaint = Paint()
    val bounds = size.toRect()
    onDrawWithContent {
        // An explicit layer, so SrcIn only sees this content even on software canvases.
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, layerPaint)
            drawContent()
            drawRect(brush, blendMode = BlendMode.SrcIn)
            canvas.restore()
        }
    }
}

private const val DEFAULT_GLOW = 0.55f
private const val GLOW_LAYERS = 4
private const val GLOW_PEAK = 0.26f
private const val INNER_INSET = 4f
private const val ACCENT_FRACTION = 0.22f
private const val MIN_ACCENT_WIDTH = 120f
