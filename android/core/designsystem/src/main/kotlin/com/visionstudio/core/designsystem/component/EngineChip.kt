package com.visionstudio.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.modifier.frostedGlass
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioSuccess
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.StudioWarning
import com.visionstudio.core.designsystem.theme.VisionStudioTheme

/**
 * Header pill showing which engine a studio is wired to: a green (ready) or amber (needs
 * setup) beacon, the engine's short name in mono, and a tune glyph. Tapping opens settings.
 */
@Composable
fun EngineChip(
    configured: Boolean,
    label: String,
    emptyLabel: String,
    onClick: () -> Unit,
    onClickLabel: String,
    modifier: Modifier = Modifier,
) {
    val accent = if (configured) StudioSuccess else StudioWarning
    Row(
        modifier = modifier
            .frostedGlass(shape = StudioShapes.Chip, border = SolidColor(accent.copy(alpha = 0.4f)))
            .semantics { contentDescription = onClickLabel }
            .tactileClickable(haptic = HapticPattern.Click, role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .frostedGlass(shape = StudioShapes.Orb, fill = accent),
        )
        Text(
            text = label.ifBlank { emptyLabel },
            style = VisionStudioTheme.typography.monoSmall,
            color = StudioTextSecondary,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(Icons.Rounded.Tune, contentDescription = null, tint = StudioTextSecondary, modifier = Modifier.size(16.dp))
    }
}
