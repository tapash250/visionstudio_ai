package com.visionstudio.feature.gallery.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.visionstudio.core.designsystem.component.BlurHashImage
import com.visionstudio.core.designsystem.component.GhostButton
import com.visionstudio.core.designsystem.component.PrismButton
import com.visionstudio.core.designsystem.component.SectionHeader
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.rememberStudioHaptics
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.modifier.frostedGlass
import com.visionstudio.core.designsystem.theme.StudioCharcoal
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioError
import com.visionstudio.core.designsystem.theme.StudioOnyx
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioSteel
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import com.visionstudio.core.player.LoopingVideoPlayer
import com.visionstudio.feature.gallery.ExportState
import com.visionstudio.feature.gallery.R
import com.visionstudio.feature.gallery.model.InspectorField
import com.visionstudio.feature.gallery.model.InspectorRow
import com.visionstudio.feature.gallery.model.toInspector
import java.time.ZoneId

/**
 * Bottom sheet for one asset: the full-size image or a looping video, the prompt, and a table
 * of the facts stored in Room (seed, model, timestamp, size and so on). Seed, model and prompt
 * copy with a tap. Delete is a deliberate two-step: the first tap arms it, the second confirms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InspectorSheet(
    item: MediaItem,
    export: ExportState,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val locale = LocalConfiguration.current.locales[0]
    val inspector = remember(item, locale) { item.toInspector(locale, ZoneId.systemDefault()) }
    val clipboard = LocalClipboardManager.current
    val haptics = rememberStudioHaptics()
    var copiedField by remember(item.id) { mutableStateOf<String?>(null) }
    var armedDelete by remember(item.id) { mutableStateOf(false) }

    fun copy(label: String, text: String) {
        clipboard.setText(AnnotatedString(text))
        haptics.confirm()
        copiedField = label
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StudioCharcoal,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = StudioOnyx.copy(alpha = 0.7f),
        shape = StudioShapes.Sheet,
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(StudioShapes.Chip)
                    .frostedGlass(shape = StudioShapes.Chip, fill = StudioSteel),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Preview(item)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(stringResource(R.string.sheet_prompt)) {
                    CopyChip(
                        label = stringResource(R.string.sheet_copy_prompt),
                        done = copiedField == PROMPT_COPY_KEY,
                        onClick = { copy(PROMPT_COPY_KEY, inspector.prompt) },
                    )
                }
                Text(inspector.prompt, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                inspector.negativePrompt?.let { negative ->
                    Text(stringResource(R.string.sheet_negative).uppercase(), style = VisionStudioTheme.typography.eyebrow, color = StudioTextSecondary, modifier = Modifier.padding(top = 6.dp))
                    Text(negative, style = MaterialTheme.typography.bodyMedium, color = StudioTextSecondary)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionHeader(stringResource(R.string.sheet_details), Modifier.padding(bottom = 6.dp))
                inspector.rows.forEach { row ->
                    DetailRow(row = row, copied = copiedField == row.field.name, onCopy = { text -> copy(row.field.name, text) })
                }
            }

            ExportActions(export = export, onSave = onSave, onShare = onShare)

            DeleteButton(armed = armedDelete, onClick = { if (armedDelete) onDelete() else armedDelete = true })
        }
    }
}

@Composable
private fun Preview(item: MediaItem) {
    val description = stringResource(R.string.sheet_preview_description, item.parameters.prompt)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = PREVIEW_MAX_HEIGHT)
            .aspectRatio((item.width.toFloat() / item.height).coerceIn(0.5f, 2.2f))
            .clip(StudioShapes.Card),
    ) {
        BlurHashImage(hash = item.blurHash, modifier = Modifier.matchParentSize())
        if (item.type == MediaType.VIDEO) {
            LoopingVideoPlayer(uri = item.localUri, description = description, modifier = Modifier.matchParentSize())
        } else {
            AsyncImage(
                model = item.localUri,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun DetailRow(row: InspectorRow, copied: Boolean, onCopy: (String) -> Unit) {
    val label = fieldLabel(row.field)
    val value = if (row.field == InspectorField.SOURCE) stringResource(R.string.field_source_photo) else row.value
    val copyDescription = stringResource(R.string.field_copy_value, label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(StudioShapes.Control)
            .then(
                if (row.technical) {
                    Modifier
                        .semantics { contentDescription = copyDescription }
                        .tactileClickable(haptic = HapticPattern.Tick, onClick = { onCopy(row.value) })
                } else {
                    Modifier
                },
            )
            .padding(vertical = 9.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label.uppercase(), style = VisionStudioTheme.typography.eyebrow, color = StudioTextSecondary)
        Text(
            text = if (copied) stringResource(R.string.sheet_copied) else value,
            style = if (row.technical) VisionStudioTheme.typography.mono else MaterialTheme.typography.bodyMedium,
            color = if (copied) StudioCyan else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun CopyChip(label: String, done: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .frostedGlass(shape = StudioShapes.Chip)
            .tactileClickable(haptic = HapticPattern.Tick, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = if (done) StudioCyan else StudioTextSecondary, modifier = Modifier.size(13.dp))
        Text(
            text = if (done) stringResource(R.string.sheet_copied) else label,
            style = MaterialTheme.typography.labelSmall,
            color = if (done) StudioCyan else StudioTextSecondary,
        )
    }
}

@Composable
private fun ExportActions(export: ExportState, onSave: () -> Unit, onShare: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PrismButton(
                text = stringResource(R.string.sheet_save),
                onClick = onSave,
                enabled = export !is ExportState.Saved,
                loading = export is ExportState.Saving,
                loadingText = stringResource(R.string.sheet_saving),
                icon = Icons.Rounded.SaveAlt,
                modifier = Modifier.weight(0.62f),
            )
            GhostButton(
                text = stringResource(R.string.sheet_share),
                onClick = onShare,
                icon = Icons.Rounded.IosShare,
                modifier = Modifier.weight(0.38f),
            )
        }
        val message = when (export) {
            is ExportState.Saved -> stringResource(
                if (export.metadataEmbedded) R.string.sheet_saved_with_settings else R.string.sheet_saved,
                export.folder,
            )
            is ExportState.Failed -> export.message
            else -> null
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (export is ExportState.Failed) StudioError else StudioCyan,
            )
        }
    }
}

@Composable
private fun DeleteButton(armed: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(if (armed) Color.White else StudioError, label = "deleteTint")
    val fill by animateColorAsState(if (armed) StudioError else StudioError.copy(alpha = 0.10f), label = "deleteFill")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(StudioShapes.Chip)
            .drawWithCache { onDrawBehind { drawRect(fill) } }
            .tactileClickable(haptic = if (armed) HapticPattern.Reject else HapticPattern.Click, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            text = stringResource(if (armed) R.string.sheet_delete_confirm else R.string.sheet_delete),
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun fieldLabel(field: InspectorField): String = stringResource(
    when (field) {
        InspectorField.SEED -> R.string.field_seed
        InspectorField.MODEL -> R.string.field_model
        InspectorField.CREATED -> R.string.field_created
        InspectorField.SIZE -> R.string.field_size
        InspectorField.FRAME -> R.string.field_frame
        InspectorField.DURATION -> R.string.field_duration
        InspectorField.CAMERA -> R.string.field_camera
        InspectorField.STEPS -> R.string.field_steps
        InspectorField.GUIDANCE -> R.string.field_guidance
        InspectorField.FILE_SIZE -> R.string.field_file_size
        InspectorField.SOURCE -> R.string.field_source
    },
)

private const val PROMPT_COPY_KEY = "prompt"
private val PREVIEW_MAX_HEIGHT = 380.dp
