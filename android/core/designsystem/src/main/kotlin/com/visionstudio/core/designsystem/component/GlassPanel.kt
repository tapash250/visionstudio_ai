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
import com.visionstudio.core.designsystem.modifier.hudFrame
import com.visionstudio.core.designsystem.theme.StudioShapes

/**
 * Neon HUD container used for docks, cards and stages: chamfered navy glass with a gradient
 * frame. Every panel carries a soft glow; [glowing] raises it to full strength, scaled by
 * [glowIntensity], which is sampled at draw time so it can be animated cheaply.
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
    Box(
        modifier = modifier
            .hudFrame(shape = shape, glow = if (glowing) glowIntensity else RESTING_GLOW)
            .padding(contentPadding),
        contentAlignment = contentAlignment,
        content = content,
    )
}

private val RESTING_GLOW: () -> Float = { 0.4f }
