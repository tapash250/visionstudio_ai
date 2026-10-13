package com.visionstudio.feature.t2i.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.visionstudio.core.designsystem.component.BlurHashImage
import com.visionstudio.core.designsystem.component.GhostButton
import com.visionstudio.core.designsystem.component.PrismButton
import com.visionstudio.core.designsystem.haptics.rememberStudioHaptics
import com.visionstudio.core.designsystem.modifier.frostedGlass
import com.visionstudio.core.designsystem.modifier.glowingBorder
import com.visionstudio.core.designsystem.modifier.shimmer
import com.visionstudio.core.designsystem.theme.StudioCharcoal
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioError
import com.visionstudio.core.designsystem.theme.StudioIndigo
import com.visionstudio.core.designsystem.theme.StudioOnyx
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.StudioViolet
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.core.model.MediaItem
import com.visionstudio.feature.t2i.R
import com.visionstudio.feature.t2i.StagePhase
import kotlin.math.roundToInt

/**
 * The canvas: a frame that morphs on a spring to the chosen aspect ratio and hosts four
 * states. Rendering: shimmer, streamed latent previews that sharpen as progress climbs, a
 * prism scan line and a large progress numeral. Showing: BlurHash under the full image,
 * revealed with a settle. Empty and Failed: glass cards.
 */
@Composable
internal fun RenderStage(
    phase: StagePhase,
    ratio: Float,
    engineConfigured: Boolean,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animatedRatio by animateFloatAsState(
        targetValue = ratio.coerceIn(MIN_RATIO, MAX_RATIO),
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "stageRatio",
    )
    val haptics = rememberStudioHaptics()
    val rendering = phase is StagePhase.Rendering
    val pulse = rememberInfiniteTransition(label = "stagePulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
        label = "stagePulseValue",
    )
    LaunchedEffect(phase) {
        when {
            phase is StagePhase.Showing && phase.fresh -> haptics.confirm()
            phase is StagePhase.Failed -> haptics.reject()
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .aspectRatio(animatedRatio, matchHeightConstraintsFirst = true)
                .glowingBorder(
                    brush = STAGE_GLOW,
                    shape = StudioShapes.Panel,
                    width = 1.dp,
                    glowRadius = 16.dp,
                    intensity = { if (rendering) pulse.value else IDLE_GLOW },
                )
                .clip(StudioShapes.Panel)
                .frostedGlass(shape = StudioShapes.Panel, fill = StudioCharcoal.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = phase,
                contentKey = { it::class to (it as? StagePhase.Showing)?.item?.id },
                transitionSpec = { fadeIn(tween(STATE_FADE_MILLIS)) togetherWith fadeOut(tween(STATE_FADE_MILLIS)) },
                label = "stagePhase",
            ) { target ->
                when (target) {
                    StagePhase.Empty -> if (engineConfigured) EmptyState() else UnconfiguredState(onOpenSettings)
                    is StagePhase.Rendering -> RenderingState(target)
                    is StagePhase.Showing -> ShowingState(target.item, target.fresh)
                    is StagePhase.Failed -> FailedState(target, onRetry)
                }
            }
        }
    }
}

@Composable
private fun RenderingState(phase: StagePhase.Rendering) {
    val progress = phase.progress
    // Previews arrive coarse: blur them heavily early and let them sharpen as the render converges.
    val clarity by animateFloatAsState(progress ?: 0.15f, spring(stiffness = Spring.StiffnessVeryLow), label = "clarity")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val preview = phase.preview
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier.blur((MAX_PREVIEW_BLUR_DP * (1f - clarity)).dp)
                        } else {
                            Modifier
                        },
                    )
                    .graphicsLayer { alpha = 0.55f + 0.45f * clarity },
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .shimmer(highlight = StudioCyan.copy(alpha = if (preview == null) 0.18f else 0.08f))
                .scanLine(),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AnimatedContent(
                targetState = progress?.let { (it * PERCENT).roundToInt() },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "progressNumeral",
            ) { percent ->
                Text(
                    text = percent?.let { "$it%" } ?: stringResource(R.string.t2i_dreaming),
                    style = VisionStudioTheme.typography.progressNumeral.copy(brush = NUMERAL_BRUSH),
                )
            }
            val caption = phase.queuePosition?.let { stringResource(R.string.t2i_queue_position, it) } ?: phase.message
            if (caption != null) {
                Text(
                    text = caption.uppercase(),
                    style = VisionStudioTheme.typography.eyebrow,
                    color = StudioTextSecondary,
                    modifier = Modifier
                        .frostedGlass(shape = StudioShapes.Chip, fill = StudioOnyx.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ShowingState(item: MediaItem, fresh: Boolean) {
    val reveal = remember(item.id) { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(item.id) {
        reveal.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessVeryLow))
    }
    Box(Modifier.fillMaxSize()) {
        BlurHashImage(hash = item.blurHash, modifier = Modifier.fillMaxSize())
        AsyncImage(
            model = item.localUri,
            contentDescription = item.parameters.prompt,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val r = reveal.value
                    alpha = r.coerceIn(0f, 1f)
                    val scale = 1f + (1f - r) * REVEAL_ZOOM
                    scaleX = scale
                    scaleY = scale
                },
        )
        item.parameters.seed?.let { seed ->
            Text(
                text = stringResource(R.string.t2i_seed_value, seed.toString()),
                style = VisionStudioTheme.typography.monoSmall,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .frostedGlass(shape = StudioShapes.Chip, fill = StudioOnyx.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    val orbit = rememberInfiniteTransition(label = "emptyOrbit").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(ORBIT_MILLIS, easing = LinearEasing)),
        label = "emptyOrbitAngle",
    )
    Column(
        modifier = Modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .drawWithCache {
                    val ring = Brush.sweepGradient(listOf(StudioIndigo, StudioViolet, StudioCyan, StudioIndigo))
                    val stroke = Stroke(width = 2.dp.toPx())
                    onDrawBehind {
                        rotate(orbit.value) { drawCircle(ring, style = stroke) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(30.dp))
        }
        Text(stringResource(R.string.t2i_empty_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            stringResource(R.string.t2i_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = StudioTextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun UnconfiguredState(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Cable, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(36.dp))
        Text(stringResource(R.string.t2i_unconfigured_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            stringResource(R.string.t2i_unconfigured_body),
            style = MaterialTheme.typography.bodySmall,
            color = StudioTextSecondary,
            textAlign = TextAlign.Center,
        )
        PrismButton(text = stringResource(R.string.t2i_unconfigured_action), onClick = onOpenSettings)
    }
}

@Composable
private fun FailedState(phase: StagePhase.Failed, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = StudioError, modifier = Modifier.size(36.dp))
        Text(stringResource(R.string.t2i_failed_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(phase.message, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary, textAlign = TextAlign.Center)
        if (phase.retryable) {
            GhostButton(text = stringResource(R.string.t2i_retry), onClick = onRetry, icon = Icons.Rounded.Refresh)
        }
    }
}

/** A prism hairline sweeping top to bottom: the "scanner" reading the latent space. */
private fun Modifier.scanLine(): Modifier = composed {
    val sweep = rememberInfiniteTransition(label = "scan").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SCAN_MILLIS, easing = LinearEasing), RepeatMode.Reverse),
        label = "scanY",
    )
    drawWithCache {
        val glowHeight = 28.dp.toPx()
        val line = Brush.horizontalGradient(listOf(Color.Transparent, StudioCyan, StudioViolet, Color.Transparent))
        val glow = Brush.verticalGradient(
            listOf(Color.Transparent, StudioCyan.copy(alpha = 0.18f), Color.Transparent),
            startY = 0f,
            endY = glowHeight,
        )
        val lineHeight = 1.5.dp.toPx()
        onDrawWithContent {
            drawContent()
            translate(top = (size.height - glowHeight) * sweep.value) {
                drawRect(glow, size = size.copy(height = glowHeight))
                drawRect(line, topLeft = Offset(0f, glowHeight / 2f), size = size.copy(height = lineHeight))
            }
        }
    }
}

private val STAGE_GLOW = Brush.linearGradient(listOf(StudioIndigo, StudioViolet, StudioCyan))
private val NUMERAL_BRUSH = Brush.linearGradient(listOf(Color.White, StudioCyan))
private const val MIN_RATIO = 0.5f
private const val MAX_RATIO = 2.4f
private const val IDLE_GLOW = 0.12f
private const val PULSE_MILLIS = 1_400
private const val STATE_FADE_MILLIS = 420
private const val ORBIT_MILLIS = 6_000
private const val SCAN_MILLIS = 2_200
private const val MAX_PREVIEW_BLUR_DP = 22f
private const val REVEAL_ZOOM = 0.06f
private const val PERCENT = 100f
