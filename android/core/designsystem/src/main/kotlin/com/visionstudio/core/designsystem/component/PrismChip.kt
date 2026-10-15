package com.visionstudio.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

/**
 * Selectable glass pill. When selected it pops on a spring and gains the prism glow border.
 * [leading] receives a 0..1 selection fraction so custom glyphs can animate in sync.
 */
@Composable
fun PrismChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.(selection: () -> Float) -> Unit)? = null,
) {
    val selection = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "chipSelection",
    )
    val textColor by animateColorAsState(if (selected) Color.White else StudioTextSecondary, label = "chipText")
    Row(
        modifier = modifier
            .graphicsLayer {
                val pop = 1f + POP * selection.value
                scaleX = pop
                scaleY = pop
            }
            .glowingBorder(CHIP_GLOW, StudioShapes.Chip, width = 0.dp, glowRadius = 6.dp, intensity = { selection.value })
            .frostedGlass(
                shape = StudioShapes.Chip,
                fill = if (selected) StudioGlassFillStrong else StudioGlassFillStrong.copy(alpha = 0.06f),
                border = if (selected) CHIP_GLOW else Brush.linearGradient(listOf(Color(0x1FFFFFFF), Color(0x1FFFFFFF))),
            )
            .semantics { this.selected = selected }
            .tactileClickable(haptic = HapticPattern.Tick, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke(this) { selection.value }
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = textColor)
    }
}

private val CHIP_GLOW = Brush.horizontalGradient(listOf(StudioIndigoBright, StudioCyan))
private const val POP = 0.04f
