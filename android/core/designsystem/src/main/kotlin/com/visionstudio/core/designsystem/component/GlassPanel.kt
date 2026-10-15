package com.visionstudio.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.modifier.frostedGlass
import com.visionstudio.core.designsystem.modifier.glowingBorder
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.VisionStudioTheme

/**
 * Frosted container used for docks, cards and sheets. When [glowing] is true an accent glow
 * border is drawn; [glowIntensity] is sampled at draw time so it can be animated cheaply.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = StudioShapes.Panel,
    glowing: Boolean = false,
    glowIntensity: () -> Float = { 1f },
    contentPadding: PaddingValues = PaddingValues(16.dp),
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = VisionStudioTheme.colors
    val glowModifier = if (glowing) {
        Modifier.glowingBorder(brush = colors.glowBorder, shape = shape, intensity = glowIntensity)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .then(glowModifier)
            .frostedGlass(shape = shape, fill = colors.glassFill, border = colors.subtleBorder)
            .padding(contentPadding),
        contentAlignment = contentAlignment,
        content = content,
    )
}
