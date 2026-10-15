package com.visionstudio.feature.video.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.modifier.frostedGlass
import com.visionstudio.core.designsystem.modifier.glowingBorder
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioGlassFillStrong
import com.visionstudio.core.designsystem.theme.StudioIndigoBright
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.model.CameraMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Horizontal strip of camera moves. Each tile carries a tiny pictogram of the move; the
 * selected tile's pictogram animates so you can see what "Dolly" or "Orbit" means before you
 * commit. One shared clock drives every glyph, and glyphs read it only while drawing.
 */
@Composable
fun CameraMotionPicker(
    selected: CameraMotion,
    onSelect: (CameraMotion) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val phase = rememberInfiniteTransition(label = "motionClock").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(CLOCK_MILLIS, easing = LinearEasing)),
        label = "motionPhase",
    )
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
    ) {
        items(CameraMotion.entries, key = { it.name }) { motion ->
            MotionTile(
                motion = motion,
                selected = motion == selected,
                phase = phase,
                onClick = { if (enabled) onSelect(motion) },
            )
        }
    }
}

@Composable
private fun MotionTile(motion: CameraMotion, selected: Boolean, phase: State<Float>, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(TILE_WIDTH)
            .glowingBorder(
                brush = TILE_GLOW,
                shape = StudioShapes.Card,
                width = 0.dp,
                glowRadius = 8.dp,
                intensity = { if (selected) 1f else 0f },
            )
            .frostedGlass(
                shape = StudioShapes.Card,
                fill = if (selected) StudioGlassFillStrong else StudioGlassFillStrong.copy(alpha = 0.06f),
                border = if (selected) TILE_GLOW else Brush.linearGradient(listOf(Color(0x1FFFFFFF), Color(0x1FFFFFFF))),
            )
            .semantics { this.selected = selected }
            .tactileClickable(haptic = HapticPattern.Tick, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MotionGlyph(motion = motion, active = selected, phase = phase, modifier = Modifier.size(34.dp))
        Text(
            text = motion.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color.White else StudioTextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** Pictogram of one camera move. Animates only while [active]; otherwise rests on a still pose. */
@Composable
internal fun MotionGlyph(motion: CameraMotion, active: Boolean, phase: State<Float>, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.drawWithCache {
            val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
            val side = size.minDimension
            val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
            onDrawBehind {
                drawMotion(motion, if (active) phase.value else REST_POSE, if (active) StudioCyan else StudioTextSecondary, stroke, origin, side)
            }
        },
    )
}

/** Draws [motion] in a [side]-pixel square at [origin]. [t] is the 0..1 animation phase. */
internal fun DrawScope.drawMotion(motion: CameraMotion, t: Float, color: Color, stroke: Stroke, origin: Offset, side: Float) {
    fun px(fraction: Float) = fraction * side
    fun at(x: Float, y: Float) = Offset(origin.x + px(x), origin.y + px(y))
    val fade = (sin(t * PI.toFloat())).coerceIn(0f, 1f) // 0 at the ends, 1 mid-move: arrows appear and vanish smoothly
    val width = stroke.width

    // The frame every move happens inside.
    drawRect(color.copy(alpha = FRAME_ALPHA), topLeft = at(0.06f, 0.18f), size = Size(px(0.88f), px(0.64f)), style = stroke)

    fun arrow(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val alpha = 0.35f + 0.65f * fade
        drawLine(color.copy(alpha = alpha), at(fromX, fromY), at(toX, toY), width, StrokeCap.Round)
        val dx = toX - fromX
        val dy = toY - fromY
        val length = hypot(dx, dy).coerceAtLeast(1e-4f)
        val ux = dx / length
        val uy = dy / length
        val head = 0.12f
        // Two barbs swept back 35° either side of the shaft.
        val cosA = 0.82f
        val sinA = 0.57f
        drawLine(color.copy(alpha = alpha), at(toX, toY), at(toX - head * (ux * cosA - uy * sinA), toY - head * (uy * cosA + ux * sinA)), width, StrokeCap.Round)
        drawLine(color.copy(alpha = alpha), at(toX, toY), at(toX - head * (ux * cosA + uy * sinA), toY - head * (uy * cosA - ux * sinA)), width, StrokeCap.Round)
    }

    when (motion) {
        CameraMotion.STATIC -> drawCircle(color, px(0.07f), at(0.5f, 0.5f))
        CameraMotion.PAN_LEFT -> { val s = (0.5f - t) * 0.18f; arrow(0.68f + s, 0.5f, 0.32f + s, 0.5f) }
        CameraMotion.PAN_RIGHT -> { val s = (t - 0.5f) * 0.18f; arrow(0.32f + s, 0.5f, 0.68f + s, 0.5f) }
        CameraMotion.TILT_UP -> { val s = (0.5f - t) * 0.14f; arrow(0.5f, 0.68f + s, 0.5f, 0.32f + s) }
        CameraMotion.TILT_DOWN -> { val s = (t - 0.5f) * 0.14f; arrow(0.5f, 0.32f + s, 0.5f, 0.68f + s) }
        CameraMotion.ZOOM_IN -> {
            val k = 1f - 0.55f * t
            drawRect(color.copy(alpha = 0.4f + 0.6f * (1f - t)), at(0.5f - 0.3f * k, 0.5f - 0.2f * k), Size(px(0.6f * k), px(0.4f * k)), style = stroke)
        }
        CameraMotion.ZOOM_OUT -> {
            val k = 0.45f + 0.55f * t
            drawRect(color.copy(alpha = 0.4f + 0.6f * t), at(0.5f - 0.3f * k, 0.5f - 0.2f * k), Size(px(0.6f * k), px(0.4f * k)), style = stroke)
        }
        CameraMotion.DOLLY_FORWARD -> {
            val k = 0.55f - 0.3f * t
            val left = 0.5f - 0.44f * k
            val right = 0.5f + 0.44f * k
            val top = 0.5f - 0.32f * k
            val bottom = 0.5f + 0.32f * k
            drawRect(color, at(left, top), Size(px(right - left), px(bottom - top)), style = stroke)
            drawLine(color.copy(alpha = 0.6f), at(0.06f, 0.18f), at(left, top), width, StrokeCap.Round)
            drawLine(color.copy(alpha = 0.6f), at(0.94f, 0.18f), at(right, top), width, StrokeCap.Round)
            drawLine(color.copy(alpha = 0.6f), at(0.06f, 0.82f), at(left, bottom), width, StrokeCap.Round)
            drawLine(color.copy(alpha = 0.6f), at(0.94f, 0.82f), at(right, bottom), width, StrokeCap.Round)
        }
        CameraMotion.ORBIT -> {
            drawArc(
                color = color.copy(alpha = 0.5f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = at(0.28f, 0.34f),
                size = Size(px(0.44f), px(0.32f)),
                style = stroke,
            )
            val angle = t * 2f * PI.toFloat()
            drawCircle(color, px(0.06f), at(0.5f + 0.22f * cos(angle), 0.5f + 0.16f * sin(angle)))
        }
    }
}

private val TILE_WIDTH = 84.dp
private val TILE_GLOW = Brush.horizontalGradient(listOf(StudioIndigoBright, StudioCyan))
private const val CLOCK_MILLIS = 1_800
private const val REST_POSE = 0.5f
private const val FRAME_ALPHA = 0.55f
