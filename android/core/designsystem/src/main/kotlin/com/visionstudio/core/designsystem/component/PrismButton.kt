package com.visionstudio.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.modifier.frostedGlass
import com.visionstudio.core.designsystem.modifier.glowingBorder
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioIndigo
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioSlate
import com.visionstudio.core.designsystem.theme.StudioTextTertiary
import com.visionstudio.core.designsystem.theme.StudioViolet

/**
 * The single primary call-to-action per screen: a prism-gradient pill with a light sheen
 * sweeping across it every few seconds, a breathing outer glow, and a spring press. While
 * [loading], the label cross-fades to [loadingText] beside an orbiting arc.
 */
@Composable
fun PrismButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingText: String = text,
    icon: ImageVector? = null,
) {
    val interactive = enabled && !loading
    val transition = rememberInfiniteTransition(label = "prism")
    val sheen = transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(SHEEN_SWEEP_MILLIS, easing = LinearEasing),
            initialStartOffset = StartOffset(SHEEN_REST_MILLIS),
        ),
        label = "sheen",
    )
    val breath = transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MILLIS), RepeatMode.Reverse),
        label = "breath",
    )
    val spin = transition.animateFloat(
        initialValue = 0f,
        targetValue = FULL_TURN,
        animationSpec = infiniteRepeatable(tween(SPIN_MILLIS, easing = LinearEasing)),
        label = "spin",
    )
    val activeFraction by animateFloatAsState(if (enabled) 1f else 0f, label = "prismEnabled")

    val shape = StudioShapes.Chip
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .glowingBorder(
                brush = PRISM_GLOW,
                shape = shape,
                width = 0.dp,
                glowRadius = 14.dp,
                intensity = { breath.value * activeFraction },
            )
            .clip(shape)
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val prism = Brush.horizontalGradient(listOf(StudioIndigo, StudioViolet, StudioCyan))
                val band = size.width * SHEEN_BAND
                val sheenBrush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.32f), Color.Transparent),
                    start = Offset.Zero,
                    end = Offset(band, size.height * 0.35f),
                )
                val disabledFill = SolidColor(StudioSlate)
                val innerHighlight = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                    endY = size.height * 0.55f,
                )
                val hairline = Stroke(width = 1.dp.toPx())
                val hairlineBrush = SolidColor(Color.White.copy(alpha = 0.18f))
                val width = size.width
                onDrawBehind {
                    val active = activeFraction
                    if (active < 1f) drawOutline(outline, disabledFill)
                    if (active > 0f) {
                        drawOutline(outline, prism, alpha = active)
                        translate(left = width * sheen.value - band / 2f) {
                            drawRect(sheenBrush, size = size.copy(width = band), alpha = active)
                        }
                    }
                    drawOutline(outline, innerHighlight)
                    drawOutline(outline, hairlineBrush, style = hairline)
                }
            }
            .tactileClickable(
                enabled = interactive,
                haptic = HapticPattern.Confirm,
                rippleColor = Color.White,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { if (loading) stateDescription = loadingText }
            .padding(horizontal = 28.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = loading,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith fadeOut() },
            label = "prismLabel",
        ) { isLoading ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    isLoading -> OrbitSpinner(angle = { spin.value })
                    icon != null -> Icon(icon, contentDescription = null, tint = labelColor(enabled), modifier = Modifier.size(20.dp))
                }
                Text(
                    text = if (isLoading) loadingText else text,
                    style = MaterialTheme.typography.labelLarge,
                    color = labelColor(enabled),
                )
            }
        }
    }
}

/** Secondary action that sits beside a [PrismButton]: frosted pill, no gradient. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .frostedGlass(shape = StudioShapes.Chip)
            .tactileClickable(enabled = enabled, haptic = HapticPattern.Click, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val tint = if (enabled) MaterialTheme.colorScheme.onSurface else StudioTextTertiary
            if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Text(text = text, style = MaterialTheme.typography.labelLarge, color = tint)
        }
    }
}

@Composable
private fun OrbitSpinner(angle: () -> Float) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .drawWithCache {
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                val track = Color.White.copy(alpha = 0.25f)
                onDrawBehind {
                    drawCircle(track, style = stroke)
                    rotate(angle()) {
                        drawArc(Color.White, startAngle = 0f, sweepAngle = SPINNER_SWEEP, useCenter = false, style = stroke)
                    }
                }
            },
    )
}

private fun labelColor(enabled: Boolean): Color = if (enabled) Color.White else StudioTextTertiary

private val PRISM_GLOW = Brush.horizontalGradient(listOf(StudioIndigo, StudioViolet, StudioCyan))
private const val SHEEN_SWEEP_MILLIS = 1_400
private const val SHEEN_REST_MILLIS = 1_800
private const val BREATH_MILLIS = 2_200
private const val SPIN_MILLIS = 900
private const val FULL_TURN = 360f
private const val SPINNER_SWEEP = 100f
private const val SHEEN_BAND = 0.35f
