package com.visionstudio.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.modifier.frostedGlass
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioIndigo
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.StudioViolet

@Immutable
data class BottomBarItem(val label: String, val icon: ImageVector)

/**
 * Floating glass navigation bar. A prism "lens" glides under the selected item on a spring and
 * its hue follows the item across the bar (indigo → violet → cyan). The selected icon lifts and
 * its label brightens; selecting an item ticks a haptic detent.
 *
 * The lens position is read only inside the draw lambda, so the glide never recomposes the bar.
 */
@Composable
fun StudioBottomBar(
    items: List<BottomBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(items.isNotEmpty()) { "StudioBottomBar needs at least one item" }
    val position = animateFloatAsState(
        targetValue = selectedIndex.coerceIn(0, items.lastIndex).toFloat(),
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "barLensPosition",
    )
    val count = items.size

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .frostedGlass(shape = StudioShapes.Dock)
            .padding(INSET)
            .drawWithCache {
                val slot = size.width / count
                val lensSize = Size(slot, size.height)
                // A full pill: the lens is as round as its height allows, matching the bar's own shape.
                val radius = CornerRadius(size.height / 2f)
                val prism = Brush.horizontalGradient(
                    colors = listOf(StudioIndigo, StudioViolet, StudioCyan),
                    startX = 0f,
                    endX = size.width,
                )
                val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), endY = size.height * 0.6f)
                val halo = 4.dp.toPx()
                onDrawBehind {
                    val x = position.value * slot
                    drawRoundRect(
                        brush = prism,
                        topLeft = Offset(x - halo, -halo),
                        size = Size(lensSize.width + halo * 2, lensSize.height + halo * 2),
                        cornerRadius = CornerRadius(radius.x + halo),
                        alpha = HALO_ALPHA,
                    )
                    drawRoundRect(prism, topLeft = Offset(x, 0f), size = lensSize, cornerRadius = radius, alpha = LENS_ALPHA)
                    drawRoundRect(sheen, topLeft = Offset(x, 0f), size = lensSize, cornerRadius = radius)
                }
            },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { index, item ->
                BarItem(
                    item = item,
                    selected = index == selectedIndex,
                    onClick = { if (index != selectedIndex) onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BarItem(item: BottomBarItem, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tint by animateColorAsState(if (selected) Color.White else StudioTextSecondary, label = "barItemTint")
    val lift = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "barItemLift",
    )
    Column(
        modifier = modifier
            .height(BAR_HEIGHT - INSET * 2)
            .semantics { this.selected = selected }
            .tactileClickable(
                haptic = if (selected) HapticPattern.None else HapticPattern.Tick,
                pressedScale = 0.94f,
                rippleColor = Color.Transparent,
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    val scale = 1f + LIFT_SCALE * lift.value
                    scaleX = scale
                    scaleY = scale
                    translationY = -LIFT_PX.dp.toPx() * lift.value
                },
        )
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private val BAR_HEIGHT = 64.dp
private val INSET = 4.dp
private const val HALO_ALPHA = 0.16f
private const val LENS_ALPHA = 0.9f
private const val LIFT_SCALE = 0.1f
private const val LIFT_PX = 1.5f
