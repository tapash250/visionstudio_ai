package com.visionstudio.core.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.haptics.rememberStudioHaptics
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioGlassFillStrong
import com.visionstudio.core.designsystem.theme.StudioIndigo
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.StudioViolet
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import kotlin.math.roundToInt

/**
 * Prism-filled track with a glowing thumb that swells on a spring while dragged. Snaps to
 * [step] increments, ticking a haptic detent on every change, and supports tap-to-jump. The
 * value is rendered in JetBrains Mono beside an eyebrow label.
 *
 * Brushes and geometry are cached per size; only the fill width and thumb position (plain
 * floats) change per frame.
 */
@Composable
fun PrismSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    step: Float = 0f,
    enabled: Boolean = true,
    onValueChangeFinished: () -> Unit = {},
) {
    val haptics = rememberStudioHaptics()
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    var dragging by remember { mutableStateOf(false) }
    val thumbScale = animateFloatAsState(
        targetValue = if (dragging) 1.35f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "thumbScale",
    )
    val span = valueRange.endInclusive - valueRange.start

    fun snap(raw: Float): Float {
        val clamped = raw.coerceIn(valueRange.start, valueRange.endInclusive)
        if (step <= 0f) return clamped
        return (valueRange.start + ((clamped - valueRange.start) / step).roundToInt() * step)
            .coerceIn(valueRange.start, valueRange.endInclusive)
    }

    fun emit(raw: Float) {
        val snapped = snap(raw)
        if (snapped != currentValue) {
            if (step > 0f) haptics.tick()
            currentOnChange(snapped)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label.uppercase(),
                style = VisionStudioTheme.typography.eyebrow,
                color = StudioTextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(text = valueText, style = VisionStudioTheme.typography.mono, color = StudioCyan)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOUCH_HEIGHT)
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, if (step > 0f) (span / step).roundToInt() - 1 else 0)
                    if (enabled) {
                        setProgress { target ->
                            emit(target)
                            true
                        }
                    }
                }
                .pointerInput(enabled, valueRange, step) {
                    if (!enabled) return@pointerInput
                    fun valueAt(x: Float) = valueRange.start + (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) * span
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        dragging = true
                        haptics.gestureStart()
                        emit(valueAt(down.position.x))
                        drag(down.id) { change ->
                            emit(valueAt(change.position.x))
                            change.consume()
                        }
                        dragging = false
                        haptics.gestureEnd()
                        currentOnFinished()
                    }
                }
                .padding(horizontal = THUMB_RADIUS)
                .drawWithCache {
                    val trackHeight = TRACK_HEIGHT.toPx()
                    val top = (size.height - trackHeight) / 2f
                    val radius = CornerRadius(trackHeight / 2f)
                    val fill = Brush.horizontalGradient(listOf(StudioIndigo, StudioViolet, StudioCyan), startX = 0f, endX = size.width)
                    val thumbRadius = THUMB_RADIUS.toPx()
                    val haloRadius = thumbRadius * HALO_FACTOR
                    val centerY = size.height / 2f
                    val trackSize = Size(size.width, trackHeight)
                    val inactive = if (enabled) StudioGlassFillStrong else StudioGlassFillStrong.copy(alpha = 0.08f)
                    onDrawBehind {
                        val fraction = if (span > 0f) ((currentValue - valueRange.start) / span).coerceIn(0f, 1f) else 0f
                        val x = size.width * fraction
                        drawRoundRect(inactive, topLeft = Offset(0f, top), size = trackSize, cornerRadius = radius)
                        drawRoundRect(fill, topLeft = Offset(0f, top), size = Size(x, trackHeight), cornerRadius = radius, alpha = if (enabled) 1f else 0.35f)
                        val scale = thumbScale.value
                        drawCircle(StudioCyan.copy(alpha = HALO_ALPHA * scale), radius = haloRadius * scale, center = Offset(x, centerY))
                        drawCircle(Color.White, radius = thumbRadius * scale, center = Offset(x, centerY))
                        drawCircle(StudioIndigo, radius = thumbRadius * scale * CORE_FACTOR, center = Offset(x, centerY))
                    }
                },
        )
    }
}

private val TOUCH_HEIGHT = 32.dp
private val TRACK_HEIGHT = 6.dp
private val THUMB_RADIUS = 9.dp
private const val HALO_FACTOR = 2.1f
private const val HALO_ALPHA = 0.22f
private const val CORE_FACTOR = 0.38f
