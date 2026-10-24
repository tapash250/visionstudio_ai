package com.visionstudio.core.designsystem.modifier

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.theme.StudioGlassFill
import com.visionstudio.core.designsystem.theme.StudioGlassHighlight
import com.visionstudio.core.designsystem.theme.StudioGlassStroke
import com.visionstudio.core.designsystem.theme.StudioShapes

/** True when the platform can render [Modifier.blur] (RenderEffect, API 31+). */
val isBlurSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Translucent glass surface: tinted fill, a soft top-edge specular highlight and a hairline
 * border. All brushes, outlines and strokes are created in the draw cache, so they are only
 * rebuilt when size, shape or inputs change — never per frame.
 *
 * Pair with [backdropBlur] on the layer *behind* the panel for a true frosted look.
 */
fun Modifier.frostedGlass(
    shape: Shape = StudioShapes.Panel,
    fill: Color = StudioGlassFill,
    border: Brush = SolidColor(StudioGlassStroke),
    borderWidth: Dp = 1.dp,
    highlight: Color = StudioGlassHighlight,
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        // The clip removes the outer half of a centred stroke; double the width so the
        // visible inner half equals the requested hairline.
        val stroke = Stroke(width = borderWidth.toPx() * 2f)
        val specular = Brush.verticalGradient(
            colors = listOf(highlight, Color.Transparent),
            startY = 0f,
            endY = size.height * SPECULAR_FRACTION,
        )
        onDrawBehind {
            drawOutline(outline, color = fill)
            drawOutline(outline, brush = specular)
            drawOutline(outline, brush = border, style = stroke)
        }
    }

/**
 * Blurs this layer's own content (apply it to the backdrop beneath a glass panel).
 * Below API 31 blur is unavailable, so the layer is dimmed instead to preserve legibility of
 * foreground glass content.
 */
fun Modifier.backdropBlur(
    radius: Dp = 24.dp,
    edgeTreatment: BlurredEdgeTreatment = BlurredEdgeTreatment.Rectangle,
    fallbackAlpha: Float = 0.45f,
): Modifier =
    if (isBlurSupported) {
        blur(radius, edgeTreatment)
    } else {
        graphicsLayer { alpha = fallbackAlpha }
    }

private const val SPECULAR_FRACTION = 0.45f
