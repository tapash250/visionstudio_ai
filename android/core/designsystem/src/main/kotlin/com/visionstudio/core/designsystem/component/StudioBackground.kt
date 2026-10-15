package com.visionstudio.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioIndigo
import com.visionstudio.core.designsystem.theme.StudioOnyx

/**
 * Root canvas for every screen: Obsidian base with two faint ambient light pools (indigo top
 * start, cyan bottom end) that give frosted glass panels something to refract.
 */
@Composable
fun StudioBackground(
    modifier: Modifier = Modifier,
    ambientGlow: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val radius = size.maxDimension * GLOW_RADIUS_FRACTION
                val indigoPool = Brush.radialGradient(
                    colors = listOf(StudioIndigo.copy(alpha = GLOW_ALPHA), Color.Transparent),
                    center = Offset(size.width * 0.1f, size.height * 0.05f),
                    radius = radius,
                )
                val cyanPool = Brush.radialGradient(
                    colors = listOf(StudioCyan.copy(alpha = GLOW_ALPHA * 0.6f), Color.Transparent),
                    center = Offset(size.width * 0.95f, size.height * 0.9f),
                    radius = radius,
                )
                onDrawBehind {
                    drawRect(StudioOnyx)
                    if (ambientGlow) {
                        drawRect(indigoPool)
                        drawRect(cyanPool)
                    }
                }
            },
        content = content,
    )
}

private const val GLOW_RADIUS_FRACTION = 0.6f
private const val GLOW_ALPHA = 0.16f
