package com.visionstudio.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioGlassFillStrong
import com.visionstudio.core.designsystem.theme.StudioIndigo
import com.visionstudio.core.designsystem.theme.StudioViolet

/**
 * Slim prism progress track. Pass a [progress] fraction for a determinate fill that eases to
 * each new value on a spring, or null for an indeterminate comet that glides across the track.
 *
 * Brushes are cached per size; the animated values are read only inside the draw lambda.
 */
@Composable
fun PrismProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    val eased = animateFloatAsState(
        targetValue = progress?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow),
        label = "progressFill",
    )
    val comet = rememberInfiniteTransition(label = "progressComet").animateFloat(
        initialValue = -COMET_WIDTH,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(COMET_MILLIS, easing = LinearEasing)),
        label = "progressCometX",
    )
    val determinate = progress != null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                if (progress != null) progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
            }
            .drawWithCache {
                val radius = CornerRadius(size.height / 2f)
                val fill = Brush.horizontalGradient(listOf(StudioIndigo, StudioViolet, StudioCyan), startX = 0f, endX = size.width)
                val cometWidth = size.width * COMET_WIDTH
                val cometBrush = Brush.horizontalGradient(
                    listOf(StudioIndigo.copy(alpha = 0f), StudioViolet, StudioCyan, StudioCyan.copy(alpha = 0f)),
                    startX = 0f,
                    endX = cometWidth,
                )
                val cometSize = Size(cometWidth, size.height)
                onDrawBehind {
                    drawRoundRect(StudioGlassFillStrong, size = size, cornerRadius = radius)
                    if (determinate) {
                        val width = size.width * eased.value
                        if (width > 0f) drawRoundRect(fill, size = Size(width, size.height), cornerRadius = radius)
                    } else {
                        // Clip the comet to the track by drawing only the on-screen part.
                        val x = size.width * comet.value
                        val visibleStart = x.coerceAtLeast(0f)
                        val visibleEnd = (x + cometWidth).coerceAtMost(size.width)
                        if (visibleEnd > visibleStart) {
                            drawRoundRect(
                                brush = cometBrush,
                                topLeft = Offset(x, 0f),
                                size = cometSize,
                                cornerRadius = radius,
                            )
                        }
                    }
                }
            },
    )
}

private const val COMET_WIDTH = 0.35f
private const val COMET_MILLIS = 1_300
