package com.visionstudio.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.R
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.modifier.gradientTint
import com.visionstudio.core.designsystem.modifier.hudFrame
import com.visionstudio.core.designsystem.theme.NeonBrushes
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioMagenta
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioTextPrimary
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.StudioViolet

/** How a [PrismSegmentedControl] shows its selection. */
enum class SegmentStyle {
    /** Pointed HUD track; the selected segment is a glowing violet→blue plate that slides. */
    Tabs,

    /** Chamfered option row with dividers; the selection lights its glyph and a sliding underline. */
    Options,
}

/**
 * Neon HUD segmented control. In [SegmentStyle.Tabs] a gradient plate springs between
 * segments; in [SegmentStyle.Options] the selected option's glyph turns to gradient and a glowing
 * underline glides beneath it. [leadingIcon] adds a gradient glyph at the start of the row, and
 * [cycleChevron] adds a trailing chevron that steps to the next option. Each change ticks a
 * haptic detent. Positions are read only in the draw phase, so sliding never recomposes.
 */
@Composable
fun <T> PrismSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector?)? = null,
    height: Dp = 56.dp,
    style: SegmentStyle = SegmentStyle.Tabs,
    leadingIcon: ImageVector? = null,
    cycleChevron: Boolean = false,
) {
    require(options.isNotEmpty()) { "PrismSegmentedControl needs at least one option" }
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    val position = animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "segmentPosition",
    )
    val count = options.size
    val tabs = style == SegmentStyle.Tabs
    val trackShape = if (tabs) StudioShapes.Tab else StudioShapes.Card

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .hudFrame(shape = trackShape, accents = !tabs, clipContent = false, glow = { if (tabs) 0.6f else 0.45f })
            .padding(horizontal = if (tabs) INSET else 6.dp, vertical = INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                leadingIcon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .padding(start = 10.dp, end = 6.dp)
                    .size(26.dp)
                    .gradientTint(NeonBrushes.Glyph),
            )
            Divider()
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .drawWithCache {
                    val segment = size.width / count
                    val dividerTop = size.height * 0.24f
                    val dividerColor = Color.White.copy(alpha = 0.22f)
                    val plateSize = Size(segment, size.height)
                    val plate = StudioShapes.Tab.createOutline(plateSize, layoutDirection, this)
                    val plateGlow = Array(3) { i -> Stroke(width = (i + 1) * 3.dp.toPx()) }
                    val plateEdge = Brush.linearGradient(listOf(StudioMagenta, StudioViolet, StudioCyan))
                    val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.26f), Color.Transparent), endY = size.height * 0.55f)
                    val underlineWidth = segment * 0.46f
                    val underlineY = size.height - 2.dp.toPx()
                    val underline = Brush.horizontalGradient(listOf(StudioCyan, StudioViolet, StudioMagenta))
                    val pool = Brush.radialGradient(
                        listOf(StudioViolet.copy(alpha = 0.32f), Color.Transparent),
                        center = Offset(segment / 2f, size.height),
                        radius = segment * 0.6f,
                    )
                    onDrawBehind {
                        for (i in 1 until count) {
                            val x = i * segment
                            drawLine(dividerColor, Offset(x, dividerTop), Offset(x, size.height - dividerTop), 1.dp.toPx())
                        }
                        val x = position.value * segment
                        if (tabs) {
                            translate(left = x) {
                                for (i in 2 downTo 0) drawOutline(plate, plateEdge, alpha = 0.16f * (3 - i) / 3f, style = plateGlow[i])
                                drawOutline(plate, NeonBrushes.Selected)
                                drawOutline(plate, sheen)
                                drawOutline(plate, plateEdge, style = Stroke(1.2.dp.toPx()))
                            }
                        } else {
                            translate(left = x) {
                                drawRect(pool, size = plateSize)
                                val start = (segment - underlineWidth) / 2f
                                drawLine(underline, Offset(start, underlineY), Offset(start + underlineWidth, underlineY), 5.dp.toPx(), alpha = 0.3f)
                                drawLine(underline, Offset(start, underlineY), Offset(start + underlineWidth, underlineY), 2.dp.toPx())
                            }
                        }
                    }
                },
        ) {
            Row(Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                options.forEachIndexed { index, option ->
                    Segment(
                        label = label(option),
                        icon = icon?.invoke(option),
                        selected = index == selectedIndex,
                        tabs = tabs,
                        onClick = { onSelect(option) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (cycleChevron && count > 1) {
            Divider()
            val next = stringResource(R.string.action_next_option)
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .width(34.dp)
                    .fillMaxHeight()
                    .semantics { contentDescription = next }
                    .tactileClickable(haptic = HapticPattern.Tick, rippleColor = Color.Transparent, role = Role.Button) {
                        onSelect(options[(selectedIndex + 1) % count])
                    }
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun Segment(label: String, icon: ImageVector?, selected: Boolean, tabs: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tint by animateColorAsState(
        targetValue = when {
            selected -> Color.White
            tabs -> StudioTextPrimary.copy(alpha = 0.9f)
            else -> StudioTextSecondary
        },
        label = "segmentTint",
    )
    Row(
        modifier = modifier
            .fillMaxHeight()
            .semantics { this.selected = selected }
            .tactileClickable(
                haptic = if (selected) HapticPattern.None else HapticPattern.Tick,
                pressedScale = 0.97f,
                rippleColor = Color.Transparent,
                role = Role.Tab,
            ) { if (!selected) onClick() }
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            val glyph = Modifier.size(if (tabs) 20.dp else 24.dp)
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected || tabs) Color.White else StudioTextSecondary,
                modifier = if (!tabs && selected) glyph.gradientTint(NeonBrushes.Glyph) else if (!tabs) glyph.gradientTint(NeonBrushes.GlyphCool) else glyph,
            )
            Box(Modifier.size(8.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .width(1.dp)
            .height(26.dp)
            .drawWithCache { onDrawBehind { drawRect(Color.White.copy(alpha = 0.22f)) } },
    )
}

private val INSET = 5.dp
