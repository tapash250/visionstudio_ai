package com.visionstudio.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.modifier.frostedGlass
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioIndigo
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.StudioViolet

/**
 * Glass track with a spring-sliding glowing indicator. The indicator samples a track-wide
 * prism gradient, so its hue shifts from indigo to cyan as it travels, which is the
 * control's signature detail. Each change ticks a haptic detent.
 *
 * The indicator position is read only inside the draw lambda, so sliding never recomposes.
 */
@Composable
fun <T> PrismSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector?)? = null,
    height: Dp = 48.dp,
) {
    require(options.isNotEmpty()) { "PrismSegmentedControl needs at least one option" }
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    val position = animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "segmentPosition",
    )
    val count = options.size

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .frostedGlass(shape = StudioShapes.Chip)
            .padding(INSET)
            .drawWithCache {
                val segmentWidth = size.width / count
                val radius = CornerRadius(size.height / 2f)
                val prism = Brush.horizontalGradient(
                    colors = listOf(StudioIndigo, StudioViolet, StudioCyan),
                    startX = 0f,
                    endX = size.width,
                )
                val haloSpread = 3.dp.toPx()
                val sheen = Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                    endY = size.height * 0.6f,
                )
                val indicatorSize = Size(segmentWidth, size.height)
                onDrawBehind {
                    val x = position.value * segmentWidth
                    drawRoundRect(
                        brush = prism,
                        topLeft = Offset(x - haloSpread, -haloSpread),
                        size = Size(segmentWidth + haloSpread * 2, size.height + haloSpread * 2),
                        cornerRadius = CornerRadius(radius.x + haloSpread),
                        alpha = HALO_ALPHA,
                    )
                    drawRoundRect(prism, topLeft = Offset(x, 0f), size = indicatorSize, cornerRadius = radius)
                    drawRoundRect(sheen, topLeft = Offset(x, 0f), size = indicatorSize, cornerRadius = radius)
                }
            },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                val tint by animateColorAsState(
                    targetValue = if (isSelected) Color.White else StudioTextSecondary,
                    label = "segmentTint",
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(height - INSET * 2)
                        .semantics { this.selected = isSelected }
                        .tactileClickable(
                            haptic = if (isSelected) HapticPattern.None else HapticPattern.Tick,
                            pressedScale = 0.97f,
                            rippleColor = Color.Transparent,
                            role = Role.Tab,
                        ) { if (!isSelected) onSelect(option) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    icon?.invoke(option)?.let { vector ->
                        Icon(vector, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                        Box(Modifier.size(6.dp))
                    }
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelMedium,
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val INSET = 4.dp
private const val HALO_ALPHA = 0.18f
