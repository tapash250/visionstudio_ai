package com.visionstudio.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.theme.StudioBlue
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.VisionStudioTheme

/**
 * Neon section header: the uppercase title in glowing cyan, a run of slanted HUD stripes and a
 * rule that fades into the canvas.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = VisionStudioTheme.typography.section.copy(shadow = Shadow(StudioCyan.copy(alpha = 0.7f), Offset.Zero, blurRadius = 14f)),
            color = StudioCyan,
            modifier = Modifier.semantics { heading() },
        )
        HudStripes(Modifier.size(width = 30.dp, height = 9.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .drawWithCache {
                    val rule = Brush.horizontalGradient(listOf(StudioCyan.copy(alpha = 0.75f), StudioBlue.copy(alpha = 0.35f), Color.Transparent))
                    onDrawBehind { drawRect(rule) }
                },
        )
        trailing?.invoke(this)
    }
}

/** Four slanted parallelograms, the "///" mark beside section titles. */
@Composable
internal fun HudStripes(modifier: Modifier = Modifier, color: Color = StudioCyan) {
    Box(
        modifier.drawWithCache {
            val count = 4
            val slant = size.height * 0.7f
            val step = (size.width - slant) / (count - 1 + STRIPE_FILL)
            val stripe = step * STRIPE_FILL
            val path = Path().apply {
                for (i in 0 until count) {
                    val x = i * step
                    moveTo(x + slant, 0f)
                    lineTo(x + slant + stripe, 0f)
                    lineTo(x + stripe, size.height)
                    lineTo(x, size.height)
                    close()
                }
            }
            val brush = Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.35f)))
            onDrawBehind { drawPath(path, brush) }
        },
    )
}

private const val STRIPE_FILL = 0.6f
