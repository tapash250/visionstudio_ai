package com.visionstudio.feature.video.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.component.GlassPanel
import com.visionstudio.core.designsystem.component.PrismButton
import com.visionstudio.core.designsystem.component.PrismChip
import com.visionstudio.core.designsystem.component.PrismSegmentedControl
import com.visionstudio.core.designsystem.component.SectionHeader
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.StudioTextTertiary
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.feature.video.R
import com.visionstudio.feature.video.VideoControls
import com.visionstudio.feature.video.VideoIntent
import com.visionstudio.feature.video.VideoMode
import com.visionstudio.feature.video.VideoUiState

/**
 * Bottom glass dock: mode switch, optional first-frame photo, prompt, a spring-open drawer for
 * camera move / length / frame, and the prism Generate button.
 */
@Composable
internal fun VideoDock(
    state: VideoUiState,
    onIntent: (VideoIntent) -> Unit,
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controls = state.controls
    val rotation = animateFloatAsState(
        targetValue = if (controls.advancedOpen) 90f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "videoTuneRotation",
    )
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)),
        shape = StudioShapes.Dock,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PrismSegmentedControl(
                options = VideoMode.entries,
                selected = controls.mode,
                onSelect = { onIntent(VideoIntent.SetMode(it)) },
                label = { mode -> stringResource(if (mode == VideoMode.TEXT_TO_VIDEO) R.string.video_mode_text else R.string.video_mode_image) },
                height = 44.dp,
            )

            AnimatedVisibility(
                visible = controls.mode == VideoMode.IMAGE_TO_VIDEO,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                PhotoSlot(state = state, onPick = onPickPhoto, onRemove = { onIntent(VideoIntent.RemovePhoto) })
            }

            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val style = VisionStudioTheme.typography.prompt.copy(color = Color.White)
                BasicTextField(
                    value = controls.prompt,
                    onValueChange = { onIntent(VideoIntent.EditPrompt(it)) },
                    textStyle = style,
                    cursorBrush = SolidColor(StudioCyan),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.TopStart) {
                            if (controls.prompt.isEmpty()) {
                                Text(stringResource(R.string.video_prompt_hint), style = style.copy(color = StudioTextTertiary))
                            }
                            inner()
                        }
                    },
                )
                val toggleLabel = stringResource(if (controls.advancedOpen) R.string.video_advanced_hide else R.string.video_advanced_show)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .semantics { contentDescription = toggleLabel }
                        .tactileClickable(haptic = HapticPattern.Tick, role = Role.Switch) { onIntent(VideoIntent.ToggleAdvanced) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = if (controls.advancedOpen) StudioCyan else StudioTextSecondary,
                        modifier = Modifier.graphicsLayer { rotationZ = rotation.value },
                    )
                }
            }

            AnimatedVisibility(
                visible = controls.advancedOpen,
                enter = fadeIn() + expandVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Drawer(controls = controls, onIntent = onIntent)
            }

            val hint = when {
                controls.prompt.isBlank() -> stringResource(R.string.video_need_prompt)
                controls.mode == VideoMode.IMAGE_TO_VIDEO && state.photo == null -> stringResource(R.string.video_need_photo)
                else -> stringResource(R.string.video_generate)
            }
            PrismButton(
                text = hint,
                onClick = { onIntent(VideoIntent.Generate) },
                enabled = state.canGenerate,
                icon = Icons.Rounded.Movie,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PhotoSlot(state: VideoUiState, onPick: () -> Unit, onRemove: () -> Unit) {
    val photo = state.photo
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(StudioShapes.Card)
            .tactileClickable(haptic = HapticPattern.Click, onClick = onPick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(64.dp).clip(StudioShapes.Control),
            contentAlignment = Alignment.Center,
        ) {
            if (photo != null) {
                Image(bitmap = photo.bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp))
            } else {
                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(28.dp))
            }
        }
        Text(
            text = stringResource(if (photo != null) R.string.video_photo_change else R.string.video_photo_hint),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (photo != null) {
            val removeLabel = stringResource(R.string.video_photo_remove)
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = removeLabel,
                tint = StudioTextSecondary,
                modifier = Modifier
                    .size(32.dp)
                    .tactileClickable(haptic = HapticPattern.Tick, onClick = onRemove)
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun Drawer(controls: VideoControls, onIntent: (VideoIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader(stringResource(R.string.video_camera))
        CameraMotionPicker(selected = controls.motion, onSelect = { onIntent(VideoIntent.SetMotion(it)) })

        SectionHeader(stringResource(R.string.video_duration))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
            items(VideoControls.DURATIONS, key = { it }) { seconds ->
                PrismChip(
                    text = stringResource(R.string.video_seconds_short, seconds),
                    selected = seconds == controls.durationSeconds,
                    onClick = { onIntent(VideoIntent.SetDuration(seconds)) },
                )
            }
        }

        SectionHeader(stringResource(R.string.video_frame))
        if (controls.mode == VideoMode.IMAGE_TO_VIDEO) {
            Text(stringResource(R.string.video_frame_from_photo), style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
                items(VideoControls.ASPECTS, key = { it.name }) { ratio ->
                    PrismChip(
                        text = ratio.label,
                        selected = ratio == controls.aspectRatio,
                        onClick = { onIntent(VideoIntent.SetAspect(ratio)) },
                    )
                }
            }
        }
    }
}
