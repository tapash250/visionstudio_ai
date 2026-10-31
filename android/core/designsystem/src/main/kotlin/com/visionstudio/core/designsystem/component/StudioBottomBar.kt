package com.visionstudio.core.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.modifier.gradientTint
import com.visionstudio.core.designsystem.modifier.hudFrame
import com.visionstudio.core.designsystem.theme.NeonBrushes
import com.visionstudio.core.designsystem.theme.StudioBlue
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioMagenta
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioViolet

@Immutable
data class BottomBarItem(val label: String, val icon: ImageVector)

/**
 * Neon HUD navigation dock. Items are gradient glyphs separated by fine rules; the selected one
 * sits in a glowing hex plate. With [centerIndex] set, that item becomes the raised sparkle orb
 * in the middle of the dock, standing on a glowing pedestal, and the other items split evenly to
 * either side.
 *
 * Labels are not drawn, but each item exposes its label as its semantics text, so screen
 * readers and UI tests find items by name.
 */
@Composable
fun StudioBottomBar(
    items: List<BottomBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    centerIndex: Int? = null,
) {
    require(items.isNotEmpty()) { "StudioBottomBar needs at least one item" }
    val center = centerIndex?.takeIf { it in items.indices }
    val select: (Int) -> Unit = { index -> if (index != selectedIndex) onSelect(index) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (center != null) BAR_HEIGHT + ORB_RISE else BAR_HEIGHT),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .hudFrame(shape = StudioShapes.Dock, clipContent = false, glow = { 0.8f })
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sides = items.indices.filter { it != center }
            val left = if (center != null) sides.filter { it < center } else sides
            val right = if (center != null) sides.filter { it > center } else emptyList()
            ItemRun(left, items, selectedIndex, select)
            if (center != null) {
                Box(Modifier.width(ORB_SIZE + 12.dp))
                ItemRun(right, items, selectedIndex, select, leadingRule = true)
            }
        }
        if (center != null) {
            CenterOrb(
                item = items[center],
                selected = center == selectedIndex,
                onClick = { select(center) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun RowScope.ItemRun(
    indices: List<Int>,
    items: List<BottomBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    leadingRule: Boolean = false,
) {
    indices.forEachIndexed { position, index ->
        if (position > 0 || leadingRule) Rule()
        BarItem(
            item = items[index],
            selected = index == selectedIndex,
            onClick = { onSelect(index) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BarItem(item: BottomBarItem, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val lit = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "barItemLit",
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .semantics {
                this.selected = selected
                text = AnnotatedString(item.label)
            }
            .tactileClickable(
                haptic = if (selected) HapticPattern.None else HapticPattern.Tick,
                pressedScale = 0.92f,
                rippleColor = Color.Transparent,
                role = Role.Tab,
                onClick = onClick,
            )
            .drawWithCache {
                val plateSide = minOf(size.width, size.height) * 0.98f
                val plateSize = Size(plateSide, plateSide)
                val plate = StudioShapes.Tab.createOutline(plateSize, layoutDirection, this)
                val origin = Offset((size.width - plateSide) / 2f, (size.height - plateSide) / 2f)
                val fill = Brush.radialGradient(
                    listOf(StudioViolet.copy(alpha = 0.45f), StudioBlue.copy(alpha = 0.18f)),
                    center = Offset(plateSide / 2f, plateSide / 2f),
                    radius = plateSide * 0.7f,
                )
                val glow = Array(3) { i -> Stroke((i + 1) * 3.dp.toPx()) }
                onDrawBehind {
                    val level = lit.value
                    if (level <= 0.01f) return@onDrawBehind
                    val s = 0.8f + 0.2f * level
                    scale(s, pivot = center) {
                        translate(origin.x, origin.y) {
                            for (i in 2 downTo 0) drawOutline(plate, NeonBrushes.Glyph, alpha = 0.14f * level * (3 - i) / 3f, style = glow[i])
                            drawOutline(plate, fill, alpha = level)
                            drawOutline(plate, NeonBrushes.Glyph, alpha = level, style = Stroke(1.4.dp.toPx()))
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(26.dp)
                .graphicsLayer {
                    val scale = 1f + LIFT_SCALE * lit.value
                    scaleX = scale
                    scaleY = scale
                }
                .gradientTint(if (selected) SELECTED_GLYPH else NeonBrushes.GlyphCool),
        )
    }
}

@Composable
private fun CenterOrb(item: BottomBarItem, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val lit = animateFloatAsState(if (selected) 1f else 0.55f, label = "orbLit")
    Box(
        modifier = modifier
            .size(ORB_SIZE)
            .drawBehind {
                // Pedestal: a glowing elliptical platform under the dock.
                val pedestalY = (BAR_HEIGHT + ORB_RISE).toPx() + 2.dp.toPx()
                val pedestal = Brush.radialGradient(
                    listOf(StudioCyan.copy(alpha = 0.55f * lit.value), StudioViolet.copy(alpha = 0.25f * lit.value), Color.Transparent),
                    center = Offset(size.width / 2f, pedestalY),
                    radius = size.width * 1.1f,
                )
                scale(scaleX = 1.6f, scaleY = 0.32f, pivot = Offset(size.width / 2f, pedestalY)) {
                    drawCircle(pedestal, radius = size.width * 1.1f, center = Offset(size.width / 2f, pedestalY))
                }
            }
            .semantics {
                this.selected = selected
                text = AnnotatedString(item.label)
            }
            .tactileClickable(
                haptic = if (selected) HapticPattern.None else HapticPattern.Confirm,
                pressedScale = 0.92f,
                rippleColor = Color.Transparent,
                role = Role.Tab,
                onClick = onClick,
            )
            .drawWithCache {
                val r = size.minDimension / 2f
                val ring = Brush.sweepGradient(listOf(StudioCyan, StudioViolet, StudioMagenta, StudioBlue, StudioCyan), size.center)
                val core = Brush.radialGradient(listOf(Color(0xFF4B2FD0), Color(0xFF1B1670), Color(0xFF070B30)), size.center, r)
                val bloom = Brush.radialGradient(listOf(StudioViolet.copy(alpha = 0.5f), StudioCyan.copy(alpha = 0.15f), Color.Transparent), size.center, r * 1.5f)
                val ringWidth = 3.dp.toPx()
                val innerRing = Stroke(1.dp.toPx())
                onDrawBehind {
                    val level = lit.value
                    drawCircle(bloom, r * 1.5f, alpha = level)
                    for (i in 3 downTo 1) drawCircle(ring, r - ringWidth / 2f + i * 2.5f.dp.toPx(), alpha = 0.12f * level * (4 - i) / 3f, style = Stroke(3.dp.toPx()))
                    drawCircle(core, r - ringWidth)
                    drawCircle(ring, r - ringWidth / 2f, style = Stroke(ringWidth))
                    drawCircle(Color.White.copy(alpha = 0.25f), r * 0.72f, style = innerRing)
                    drawSparkle(center, r * 0.5f)
                    drawSparkle(Offset(center.x + r * 0.42f, center.y - r * 0.4f), r * 0.11f, plain = true)
                }
            },
    )
}

@Composable
private fun Rule() {
    Box(
        Modifier
            .width(1.dp)
            .height(30.dp)
            .drawWithCache {
                val rule = Brush.verticalGradient(listOf(Color.Transparent, StudioCyan.copy(alpha = 0.5f), Color.Transparent))
                onDrawBehind { drawRect(rule) }
            },
    )
}

private val BAR_HEIGHT = 66.dp
private val ORB_SIZE = 76.dp
private val ORB_RISE = 22.dp
private const val LIFT_SCALE = 0.12f
private val SELECTED_GLYPH = Brush.linearGradient(listOf(Color.White, StudioCyan, StudioViolet))
