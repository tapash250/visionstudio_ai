package com.visionstudio.feature.t2i.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.component.GhostButton
import com.visionstudio.core.designsystem.component.GlassPanel
import com.visionstudio.core.designsystem.component.GlowTextField
import com.visionstudio.core.designsystem.component.PrismButton
import com.visionstudio.core.designsystem.component.PrismChip
import com.visionstudio.core.designsystem.component.PrismSlider
import com.visionstudio.core.designsystem.component.SectionHeader
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.StudioTextTertiary
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.core.model.AspectRatio
import com.visionstudio.feature.t2i.R
import com.visionstudio.feature.t2i.StagePhase
import com.visionstudio.feature.t2i.T2iIntent
import com.visionstudio.feature.t2i.T2iUiState
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Bottom glass dock: prompt input on top, a spring-expanding control drawer (frame, steps,
 * guidance, seed, negative prompt), and the prism Generate action, which becomes a live
 * progress readout with a cancel button while rendering.
 */
@Composable
internal fun ModernPromptDock(
    state: T2iUiState,
    onIntent: (T2iIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val controls = state.controls
    val rendering = state.rendering
    val toggleRotation = animateFloatAsState(
        targetValue = if (controls.advancedOpen) 90f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "tuneRotation",
    )

    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)),
        shape = StudioShapes.Dock,
        glowing = rendering,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PromptInput(
                    value = controls.prompt,
                    onValueChange = { onIntent(T2iIntent.EditPrompt(it)) },
                    enabled = !rendering,
                    modifier = Modifier.weight(1f),
                )
                val toggleLabel = stringResource(if (controls.advancedOpen) R.string.t2i_advanced_hide else R.string.t2i_advanced_show)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .semantics { contentDescription = toggleLabel }
                        .tactileClickable(haptic = HapticPattern.Tick, role = Role.Switch) { onIntent(T2iIntent.ToggleAdvanced) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = if (controls.advancedOpen) StudioCyan else StudioTextSecondary,
                        modifier = Modifier.graphicsLayer { rotationZ = toggleRotation.value },
                    )
                }
            }

            AnimatedVisibility(
                visible = controls.advancedOpen,
                enter = fadeIn() + expandVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)),
                exit = fadeOut() + shrinkVertically(),
            ) {
                AdvancedControls(state = state, onIntent = onIntent)
            }

            ActionRow(state = state, onIntent = onIntent)
        }
    }
}

@Composable
private fun PromptInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val style = VisionStudioTheme.typography.prompt.copy(color = if (enabled) androidx.compose.ui.graphics.Color.White else StudioTextTertiary)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        textStyle = style,
        cursorBrush = SolidColor(StudioCyan),
        maxLines = 5,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
        modifier = modifier.heightIn(min = 48.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.TopStart) {
                if (value.isEmpty()) {
                    Text(stringResource(R.string.t2i_prompt_hint), style = style.copy(color = StudioTextTertiary))
                }
                inner()
            }
        },
    )
}

@Composable
private fun AdvancedControls(state: T2iUiState, onIntent: (T2iIntent) -> Unit) {
    val controls = state.controls
    val enabled = !state.rendering
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader(stringResource(R.string.t2i_aspect))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
            items(AspectRatio.entries, key = { it.name }) { ratio ->
                PrismChip(
                    text = ratio.label,
                    selected = ratio == controls.aspectRatio,
                    onClick = { if (enabled) onIntent(T2iIntent.SelectAspect(ratio)) },
                    leading = { selection -> AspectGlyph(ratio, selection) },
                )
            }
        }
        PrismSlider(
            value = controls.steps.toFloat(),
            onValueChange = { onIntent(T2iIntent.SetSteps(it.roundToInt())) },
            valueRange = STEPS_MIN..STEPS_MAX,
            step = 1f,
            label = stringResource(R.string.t2i_steps),
            valueText = controls.steps.toString(),
            enabled = enabled,
        )
        PrismSlider(
            value = controls.guidance,
            onValueChange = { onIntent(T2iIntent.SetGuidance(it)) },
            valueRange = GUIDANCE_MIN..GUIDANCE_MAX,
            step = GUIDANCE_STEP,
            label = stringResource(R.string.t2i_guidance),
            valueText = String.format(Locale.US, "%.1f", controls.guidance),
            enabled = enabled,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlowTextField(
                value = if (controls.randomSeed) "" else controls.seedText,
                onValueChange = { onIntent(T2iIntent.EditSeed(it)) },
                label = stringResource(R.string.t2i_seed),
                placeholder = stringResource(if (controls.randomSeed) R.string.t2i_seed_random else R.string.t2i_seed_hint),
                mono = true,
                enabled = enabled && !controls.randomSeed,
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            PrismChip(
                text = stringResource(R.string.t2i_seed_random),
                selected = controls.randomSeed,
                onClick = { if (enabled) onIntent(T2iIntent.SetRandomSeed(!controls.randomSeed)) },
                leading = { Icon(Icons.Rounded.Casino, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        GlowTextField(
            value = controls.negativePrompt,
            onValueChange = { onIntent(T2iIntent.EditNegative(it)) },
            label = stringResource(R.string.t2i_negative),
            placeholder = stringResource(R.string.t2i_negative_hint),
            enabled = enabled,
            imeAction = ImeAction.Done,
        )
    }
}

@Composable
private fun ActionRow(state: T2iUiState, onIntent: (T2iIntent) -> Unit) {
    val rendering = state.phase as? StagePhase.Rendering
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (rendering != null) {
            GhostButton(
                text = stringResource(R.string.t2i_cancel),
                onClick = { onIntent(T2iIntent.Cancel) },
                icon = Icons.Rounded.Close,
            )
        } else {
            Text(
                text = "${state.controls.aspectRatio.label} · ${state.controls.steps} steps",
                style = VisionStudioTheme.typography.monoSmall,
                color = StudioTextTertiary,
                modifier = Modifier.weight(0.4f),
            )
        }
        val loadingText = rendering?.progress?.let { stringResource(R.string.t2i_rendering, (it * 100).roundToInt()) }
            ?: stringResource(R.string.t2i_rendering_indeterminate)
        PrismButton(
            text = stringResource(R.string.t2i_generate),
            onClick = { onIntent(T2iIntent.Generate) },
            enabled = state.controls.canSubmit && state.engine.configured,
            loading = rendering != null,
            loadingText = loadingText,
            icon = Icons.Rounded.AutoAwesome,
            modifier = Modifier.weight(0.6f),
        )
    }
}

/** Miniature frame of the ratio; it brightens as its chip becomes selected. */
@Composable
private fun AspectGlyph(ratio: AspectRatio, selection: () -> Float) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .drawWithCache {
                val box = size.minDimension
                val (w, h) = if (ratio.ratio >= 1f) box to box / ratio.ratio else box * ratio.ratio to box
                val topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f)
                val frame = Size(w, h)
                val corner = CornerRadius(2.dp.toPx())
                val stroke = Stroke(width = 1.5.dp.toPx())
                onDrawBehind {
                    val s = selection()
                    drawRoundRect(StudioCyan.copy(alpha = 0.18f * s), topLeft, frame, corner)
                    drawRoundRect(
                        color = if (s > 0.5f) StudioCyan else StudioTextSecondary,
                        topLeft = topLeft,
                        size = frame,
                        cornerRadius = corner,
                        style = stroke,
                    )
                }
            },
    )
}

private const val STEPS_MIN = 1f
private const val STEPS_MAX = 60f
private const val GUIDANCE_MIN = 0f
private const val GUIDANCE_MAX = 20f
private const val GUIDANCE_STEP = 0.5f
