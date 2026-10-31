package com.visionstudio.feature.imageedit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visionstudio.core.designsystem.component.AuroraBackground
import com.visionstudio.core.designsystem.component.EngineChip
import com.visionstudio.core.designsystem.component.ScreenTitle
import com.visionstudio.core.designsystem.component.GlassPanel
import com.visionstudio.core.designsystem.component.PrismButton
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.rememberStudioHaptics
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.modifier.frostedGlass
import com.visionstudio.core.designsystem.modifier.glowingBorder
import com.visionstudio.core.designsystem.modifier.shimmer
import com.visionstudio.core.designsystem.modifier.staggeredEntrance
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioError
import com.visionstudio.core.designsystem.theme.StudioIndigoBright
import com.visionstudio.core.designsystem.theme.StudioOnyx
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.feature.imageedit.ui.CompareSlider
import com.visionstudio.feature.imageedit.ui.EditDock
import com.visionstudio.feature.imageedit.ui.InpaintMaskCanvas
import com.visionstudio.feature.imageedit.ui.MaskToolbar
import com.visionstudio.feature.imageedit.ui.ResultActions
import kotlin.math.roundToInt

@Composable
fun EditRoute(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    EditScreen(state = state, onIntent = viewModel::onIntent, onOpenSettings = onOpenSettings, modifier = modifier)
}

@Composable
fun EditScreen(
    state: EditUiState,
    onIntent: (EditIntent) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Android Photo Picker: no storage permission needed, and it falls back to the system
    // document picker on devices without the picker.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        onIntent(EditIntent.PhotoPicked(uri))
    }
    val launchPicker = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val haptics = rememberStudioHaptics()
    val phase = state.phase
    LaunchedEffect(phase is EditPhase.Result) { if (phase is EditPhase.Result) haptics.confirm() }
    LaunchedEffect(state.error) { if (state.error != null) haptics.reject() }

    AuroraBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Header(state, onOpenSettings, Modifier.staggeredEntrance(0))

            AnimatedVisibility(
                visible = state.error != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                ErrorBanner(message = state.error.orEmpty(), onDismiss = { onIntent(EditIntent.DismissError) })
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .staggeredEntrance(1),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = phase,
                    // Painting and rendering share one stage so the canvas is never torn down mid-edit.
                    contentKey = { it.stage },
                    transitionSpec = { fadeIn(tween(STAGE_FADE_MILLIS)) togetherWith fadeOut(tween(STAGE_FADE_MILLIS)) },
                    label = "editStage",
                ) { target ->
                    when (target.stage) {
                        Stage.Pick -> PickerHero(state.engineConfigured, onPick = launchPicker, onOpenSettings = onOpenSettings)
                        Stage.Loading -> LoadingCard()
                        Stage.Compare -> {
                            val source = state.source
                            if (target is EditPhase.Result && source != null) {
                                CompareSlider(
                                    before = source.bitmap,
                                    afterModel = target.item.localUri,
                                    beforeLabel = stringResource(R.string.edit_result_before),
                                    afterLabel = stringResource(R.string.edit_result_after),
                                    description = stringResource(R.string.edit_compare_description),
                                )
                            }
                        }
                        Stage.Canvas -> state.source?.let { source ->
                            val rendering = target as? EditPhase.Rendering
                            InpaintMaskCanvas(
                                source = source.bitmap,
                                history = state.history,
                                brush = state.brush,
                                onStrokeCommitted = { onIntent(EditIntent.CommitStroke(it)) },
                                enabled = rendering == null,
                                contentDescription = stringResource(R.string.edit_canvas_description),
                                overlay = { if (rendering != null) RenderingVeil(rendering) },
                            )
                        }
                    }
                }
            }

            when {
                phase is EditPhase.Result -> ResultActions(onIntent, Modifier.staggeredEntrance(2))
                phase == EditPhase.Editing || phase is EditPhase.Rendering -> {
                    MaskToolbar(state, onIntent, Modifier.staggeredEntrance(2))
                    EditDock(state, onIntent, onChangePhoto = { onIntent(EditIntent.ChangePhoto) }, modifier = Modifier.padding(bottom = 8.dp).staggeredEntrance(3))
                }
                else -> Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun Header(state: EditUiState, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    ScreenTitle(
        eyebrow = stringResource(R.string.edit_section),
        title = stringResource(R.string.edit_eyebrow),
        modifier = modifier,
    ) {
        EngineChip(
            configured = state.engineConfigured,
            label = state.engineLabel,
            emptyLabel = stringResource(R.string.edit_engine_none),
            onClick = onOpenSettings,
            onClickLabel = stringResource(R.string.edit_open_settings),
        )
    }
}

@Composable
private fun PickerHero(engineConfigured: Boolean, onPick: () -> Unit, onOpenSettings: () -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth(), shape = StudioShapes.Panel, glowing = true, glowIntensity = { 0.35f }) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = if (engineConfigured) Icons.Rounded.AddPhotoAlternate else Icons.Rounded.Cable,
                contentDescription = null,
                tint = StudioCyan,
                modifier = Modifier.size(44.dp),
            )
            Text(stringResource(R.string.edit_pick_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Text(stringResource(R.string.edit_pick_body), style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary, textAlign = TextAlign.Center)
            PrismButton(
                text = stringResource(R.string.edit_pick_action),
                onClick = onPick,
                icon = Icons.Rounded.AddPhotoAlternate,
            )
            if (!engineConfigured) {
                Text(
                    text = stringResource(R.string.edit_open_settings),
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioIndigoBright,
                    modifier = Modifier
                        .tactileClickable(haptic = HapticPattern.Click, onClick = onOpenSettings)
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .frostedGlass(shape = StudioShapes.Panel)
            .shimmer(),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.edit_loading), style = MaterialTheme.typography.labelLarge, color = StudioTextSecondary)
    }
}

/** Drawn over the canvas while the provider works: dims the photo, shows streamed previews and progress. */
@Composable
private fun RenderingVeil(phase: EditPhase.Rendering) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        phase.preview?.let { preview ->
            Image(
                bitmap = preview,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize().blur(PREVIEW_BLUR),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.55f }
                .frostedGlass(shape = StudioShapes.Card, fill = StudioOnyx)
                .shimmer(highlight = StudioCyan.copy(alpha = 0.16f)),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = phase.progress?.let { "${(it * PERCENT).roundToInt()}%" } ?: stringResource(R.string.edit_working),
                style = VisionStudioTheme.typography.progressNumeral.copy(brush = NUMERAL_BRUSH),
            )
            val caption = phase.queuePosition?.let { stringResource(R.string.edit_queue_position, it) } ?: phase.message
            if (caption != null) {
                Text(caption.uppercase(), style = VisionStudioTheme.typography.eyebrow, color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glowingBorder(SolidColor(StudioError), StudioShapes.Card, width = 1.dp, glowRadius = 6.dp, intensity = { 0.5f })
            .frostedGlass(shape = StudioShapes.Card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = StudioError, modifier = Modifier.size(20.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.edit_dismiss),
            tint = StudioTextSecondary,
            modifier = Modifier
                .size(22.dp)
                .tactileClickable(haptic = HapticPattern.Tick, onClick = onDismiss),
        )
    }
}

private enum class Stage { Pick, Loading, Canvas, Compare }

private val EditPhase.stage: Stage
    get() = when (this) {
        EditPhase.Empty -> Stage.Pick
        EditPhase.Loading -> Stage.Loading
        EditPhase.Editing, is EditPhase.Rendering -> Stage.Canvas
        is EditPhase.Result -> Stage.Compare
    }

private val NUMERAL_BRUSH = Brush.linearGradient(listOf(Color.White, StudioCyan))
private val PREVIEW_BLUR = 14.dp
private const val STAGE_FADE_MILLIS = 360
private const val PERCENT = 100f

@Preview(showBackground = true, backgroundColor = 0xFF0A0B0E, heightDp = 780)
@Composable
private fun EditEmptyPreview() {
    VisionStudioTheme {
        EditScreen(state = EditUiState(engineConfigured = true, engineLabel = "flux-fill"), onIntent = {}, onOpenSettings = {})
    }
}
