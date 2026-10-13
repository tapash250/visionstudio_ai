package com.visionstudio.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.modifier.frostedGlass
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.StudioTextTertiary

/** Round frosted button holding one glyph. Dims when disabled; [highlighted] tints it cyan. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    size: Dp = 44.dp,
) {
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> StudioTextTertiary.copy(alpha = 0.5f)
            highlighted -> StudioCyan
            else -> StudioTextSecondary
        },
        label = "iconTint",
    )
    Box(
        modifier = modifier
            .size(size)
            .frostedGlass(shape = StudioShapes.Orb)
            .semantics { contentDescription = label }
            .tactileClickable(enabled = enabled, haptic = HapticPattern.Click, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * ICON_FRACTION))
    }
}

private const val ICON_FRACTION = 0.5f
