package com.visionstudio.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioError
import com.visionstudio.core.designsystem.theme.StudioSlate
import com.visionstudio.core.designsystem.theme.StudioSuccess

enum class OrbState { Idle, Working, Success, Failure }

/**
 * Animated status beacon for async work. Working emits two staggered sonar rings, and
 * Success and Failure settle with a spring-scaled glyph. Colours cross-fade between states.
 * Everything is drawn with solid colours and precomputed strokes, so nothing is allocated per frame.
 */
@Composable
fun StatusOrb(state: OrbState, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val color by animateColorAsState(
        targetValue = when (state) {
            OrbState.Idle -> StudioSlate
            OrbState.Working -> StudioCyan
            OrbState.Success -> StudioSuccess
            OrbState.Failure -> StudioError
        },
        label = "orbColor",
    )
    val sonar = rememberInfiniteTransition(label = "orbSonar").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SONAR_MILLIS, easing = LinearEasing)),
        label = "orbSonarPhase",
    )
    val working = state == OrbState.Working

    Box(
        modifier = modifier
            .size(size)
            .drawWithCache {
                val core = this.size.minDimension * CORE_FRACTION
                val maxRing = this.size.minDimension / 2f
                val ringStroke = Stroke(width = 1.5.dp.toPx())
                onDrawBehind {
                    drawCircle(color.copy(alpha = HALO_ALPHA), radius = maxRing)
                    if (working) {
                        for (offset in RING_OFFSETS) {
                            val p = (sonar.value + offset) % 1f
                            drawCircle(
                                color = color.copy(alpha = (1f - p) * RING_ALPHA),
                                radius = core + (maxRing - core) * p,
                                style = ringStroke,
                            )
                        }
                    }
                    drawCircle(color.copy(alpha = CORE_GLOW_ALPHA), radius = core * 1.25f)
                    drawCircle(color, radius = core)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (fadeIn() + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.4f)) togetherWith fadeOut()
            },
            label = "orbGlyph",
        ) { target ->
            val glyph = when (target) {
                OrbState.Idle, OrbState.Working -> Icons.Rounded.Bolt
                OrbState.Success -> Icons.Rounded.Check
                OrbState.Failure -> Icons.Rounded.Close
            }
            Icon(
                imageVector = glyph,
                contentDescription = target.name,
                tint = if (target == OrbState.Idle) Color.White.copy(alpha = 0.6f) else Color(0xFF0A0B0E),
                modifier = Modifier.size(size * GLYPH_FRACTION),
            )
        }
    }
}

private val RING_OFFSETS = floatArrayOf(0f, 0.5f)
private const val SONAR_MILLIS = 1_600
private const val CORE_FRACTION = 0.26f
private const val GLYPH_FRACTION = 0.36f
private const val HALO_ALPHA = 0.08f
private const val RING_ALPHA = 0.7f
private const val CORE_GLOW_ALPHA = 0.3f
