package com.visionstudio.feature.imageedit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.AutoFixNormal
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.component.GhostButton
import com.visionstudio.core.designsystem.component.GlassIconButton
import com.visionstudio.core.designsystem.component.GlassPanel
import com.visionstudio.core.designsystem.component.PrismButton
import com.visionstudio.core.designsystem.component.PrismSegmentedControl
import com.visionstudio.core.designsystem.component.PrismSlider
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioTextTertiary
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.feature.imageedit.EditIntent
import com.visionstudio.feature.imageedit.EditPhase
import com.visionstudio.feature.imageedit.EditUiState
import com.visionstudio.feature.imageedit.R
import com.visionstudio.feature.imageedit.mask.BrushSettings
import java.util.Locale
import kotlin.math.roundToInt

/** Brush/eraser toggle, size slider, and undo, redo, clear and change-photo buttons. */
@Composable
internal fun MaskToolbar(
    state: EditUiState,
    onIntent: (EditIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.rendering
    GlassPanel(modifier = modifier.fillMaxWidth(), shape = StudioShapes.Panel, contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrismSegmentedControl(
                    options = listOf(false, true),
                    selected = state.brush.erase,
                    onSelect = { onIntent(EditIntent.SetErasing(it)) },
                    label = { erase -> stringResource(if (erase) R.string.edit_tool_eraser else R.string.edit_tool_brush) },
                    icon = { erase -> if (erase) Icons.Rounded.AutoFixNormal else Icons.Rounded.Brush },
                    height = 44.dp,
                    modifier = Modifier.weight(1f),
                )
                GlassIconButton(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.edit_undo), { onIntent(EditIntent.Undo) }, enabled = enabled && state.history.canUndo)
                GlassIconButton(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.edit_redo), { onIntent(EditIntent.Redo) }, enabled = enabled && state.history.canRedo)
                GlassIconButton(Icons.Rounded.DeleteSweep, stringResource(R.string.edit_clear), { onIntent(EditIntent.ClearMask) }, enabled = enabled && !state.history.isEmpty)
            }
            PrismSlider(
                value = state.brush.diameterFraction,
                onValueChange = { onIntent(EditIntent.SetBrushDiameter(it)) },
                valueRange = BrushSettings.MIN_DIAMETER..BrushSettings.MAX_DIAMETER,
                step = BRUSH_STEP,
                label = stringResource(R.string.edit_brush_size),
                valueText = stringResource(R.string.edit_brush_size_value, (state.brush.diameterFraction * PERCENT).roundToInt()),
                enabled = enabled,
            )
        }
    }
}

/** Prompt, strength, and the Repaint / Cancel action. */
@Composable
internal fun EditDock(
    state: EditUiState,
    onIntent: (EditIntent) -> Unit,
    onChangePhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rendering = state.phase as? EditPhase.Rendering
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        shape = StudioShapes.Dock,
        glowing = rendering != null,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val style = VisionStudioTheme.typography.prompt.copy(color = if (rendering == null) Color.White else StudioTextTertiary)
            BasicTextField(
                value = state.prompt,
                onValueChange = { onIntent(EditIntent.EditPrompt(it)) },
                enabled = rendering == null,
                textStyle = style,
                cursorBrush = SolidColor(StudioCyan),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.TopStart) {
                        if (state.prompt.isEmpty()) {
                            Text(stringResource(R.string.edit_prompt_hint), style = style.copy(color = StudioTextTertiary))
                        }
                        inner()
                    }
                },
            )
            PrismSlider(
                value = state.strength,
                onValueChange = { onIntent(EditIntent.SetStrength(it)) },
                valueRange = 0.1f..1f,
                step = STRENGTH_STEP,
                label = stringResource(R.string.edit_strength),
                valueText = String.format(Locale.US, "%.2f", state.strength),
                enabled = rendering == null,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (rendering != null) {
                    GhostButton(stringResource(R.string.edit_cancel), { onIntent(EditIntent.Cancel) }, icon = Icons.Rounded.Close)
                } else {
                    GlassIconButton(Icons.Rounded.PhotoLibrary, stringResource(R.string.edit_change_photo), onChangePhoto, size = 56.dp)
                }
                val loadingText = rendering?.progress?.let { stringResource(R.string.edit_rendering, (it * PERCENT).roundToInt()) }
                    ?: stringResource(R.string.edit_rendering_indeterminate)
                PrismButton(
                    text = stringResource(if (state.history.isEmpty && rendering == null) R.string.edit_paint_first else R.string.edit_repaint),
                    onClick = { onIntent(EditIntent.Repaint) },
                    enabled = state.canRepaint,
                    loading = rendering != null,
                    loadingText = loadingText,
                    icon = Icons.Rounded.AutoFixHigh,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Actions shown beneath a finished edit. */
@Composable
internal fun ResultActions(onIntent: (EditIntent) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        GhostButton(stringResource(R.string.edit_keep_editing), { onIntent(EditIntent.KeepEditing) }, icon = Icons.Rounded.Brush, modifier = Modifier.weight(0.45f))
        PrismButton(
            text = stringResource(R.string.edit_use_result),
            onClick = { onIntent(EditIntent.UseResultAsSource) },
            icon = Icons.Rounded.AutoFixHigh,
            modifier = Modifier.weight(0.55f),
        )
    }
}

private const val BRUSH_STEP = 0.005f
private const val STRENGTH_STEP = 0.05f
private const val PERCENT = 100f
