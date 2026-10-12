package com.visionstudio.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.visionstudio.app.R
import com.visionstudio.core.designsystem.component.GlassPanel
import com.visionstudio.core.designsystem.component.StudioBackground
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot

@Immutable
data class EngineSlotSummary(
    val slot: EngineSlot,
    val protocolLabel: String,
    val isConfigured: Boolean,
)

/**
 * Pre-navigation landing surface. Ticket 4.3 replaces this with the bottom-bar NavHost; until
 * then it exercises the design system end to end on device.
 */
@Composable
fun StudioShell(modifier: Modifier = Modifier) {
    val slots = remember {
        EngineSlot.entries.map { slot ->
            val config = EngineConfig.unconfigured(slot)
            EngineSlotSummary(slot, config.protocol.displayName, config.isConfigured)
        }
    }
    val pulse by rememberInfiniteTransition(label = "heroPulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "heroPulseValue",
    )

    StudioBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlassPanel(
                modifier = Modifier.fillMaxWidth(),
                glowing = true,
                glowIntensity = { pulse },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.shell_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Text(
                text = stringResource(R.string.shell_engines_header),
                style = VisionStudioTheme.typography.eyebrow,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )

            slots.forEach { summary -> EngineSlotRow(summary) }
        }
    }
}

@Composable
private fun EngineSlotRow(summary: EngineSlotSummary) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = StudioShapes.Card,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = summary.slot.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = summary.protocolLabel,
                    style = VisionStudioTheme.typography.monoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    if (summary.isConfigured) R.string.shell_slot_ready else R.string.shell_slot_unconfigured,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (summary.isConfigured) {
                    VisionStudioTheme.colors.success
                } else {
                    VisionStudioTheme.colors.warning
                },
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0B0E)
@Composable
private fun StudioShellPreview() {
    VisionStudioTheme { StudioShell() }
}
