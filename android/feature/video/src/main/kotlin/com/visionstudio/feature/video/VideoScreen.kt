package com.visionstudio.feature.video

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visionstudio.core.data.video.VideoJobStatus
import com.visionstudio.core.designsystem.component.AuroraBackground
import com.visionstudio.core.designsystem.component.EngineChip
import com.visionstudio.core.designsystem.component.GlassPanel
import com.visionstudio.core.designsystem.component.PrismButton
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.rememberStudioHaptics
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.modifier.frostedGlass
import com.visionstudio.core.designsystem.modifier.glowingBorder
import com.visionstudio.core.designsystem.modifier.staggeredEntrance
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioError
import com.visionstudio.core.designsystem.theme.StudioIndigoBright
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.StudioViolet
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.feature.video.ui.JobStage
import com.visionstudio.feature.video.ui.LoopingVideoPlayer
import com.visionstudio.feature.video.ui.RendersStrip
import com.visionstudio.feature.video.ui.VideoDock

@Composable
fun VideoRoute(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VideoViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    VideoScreen(state = state, onIntent = viewModel::onIntent, onOpenSettings = onOpenSettings, modifier = modifier)
}

@Composable
fun VideoScreen(
    state: VideoUiState,
    onIntent: (VideoIntent) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = rememberStudioHaptics()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        onIntent(VideoIntent.PhotoPicked(uri))
    }

    // Completion alerts need the notification permission on Android 13+. Ask once, at the moment
    // it matters; the render proceeds either way (the alert just won't be shown if declined).
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onIntent(VideoIntent.Generate)
    }
    val generate = {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !askedForNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            askedForNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onIntent(VideoIntent.Generate)
        }
    }

    val failedFocus = state.focusedJob?.status == VideoJobStatus.FAILED
    LaunchedEffect(failedFocus) { if (failedFocus) haptics.reject() }
    LaunchedEffect(state.focusedItem?.id) { if (state.focusedItem != null) haptics.confirm() }

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
                visible = state.notice != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                NoticeBanner(message = state.notice.orEmpty(), onDismiss = { onIntent(VideoIntent.DismissNotice) })
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .staggeredEntrance(1),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = state.focus,
                    contentKey = { focus -> focus::class to (focus as? Focus.Job)?.id.orEmpty() + (focus as? Focus.Item)?.id.orEmpty() },
                    transitionSpec = { fadeIn(tween(STAGE_FADE_MILLIS)) togetherWith fadeOut(tween(STAGE_FADE_MILLIS)) },
                    label = "videoStage",
                ) { focus ->
                    val job = (focus as? Focus.Job)?.let { f -> state.jobs.firstOrNull { it.id == f.id } }
                    val item = (focus as? Focus.Item)?.let { f -> state.recent.firstOrNull { it.id == f.id } }
                    when {
                        job != null -> JobStage(
                            job = job,
                            onCancel = { onIntent(VideoIntent.CancelJob(job.id)) },
                            onRetry = { onIntent(VideoIntent.RetryJob(job.id)) },
                            onDismiss = { onIntent(VideoIntent.DismissJob(job.id)) },
                        )
                        item != null -> {
                            val cornerPx = with(LocalDensity.current) { PLAYER_CORNER.toPx() }
                            LoopingVideoPlayer(
                                uri = item.localUri,
                                description = stringResource(R.string.video_player_description, item.parameters.prompt),
                                cornerRadiusPx = cornerPx,
                                modifier = Modifier
                                    .aspectRatio(item.width.toFloat() / item.height.toFloat())
                                    .clip(StudioShapes.Panel)
                                    .glowingBorder(STAGE_GLOW, StudioShapes.Panel, width = 1.dp, glowRadius = 14.dp, intensity = { 0.35f }),
                            )
                        }
                        else -> if (state.engineConfigured) EmptyStage() else UnconfiguredStage(onOpenSettings)
                    }
                }
            }

            AnimatedVisibility(
                visible = (state.jobs.isNotEmpty() || state.recent.isNotEmpty()) && !state.controls.advancedOpen,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                RendersStrip(
                    state = state,
                    onFocusJob = { onIntent(VideoIntent.FocusJob(it)) },
                    onFocusItem = { onIntent(VideoIntent.FocusItem(it)) },
                )
            }

            VideoDock(
                state = state,
                onIntent = { intent -> if (intent == VideoIntent.Generate) generate() else onIntent(intent) },
                onPickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.padding(bottom = 8.dp).staggeredEntrance(2),
            )
        }
    }
}

@Composable
private fun Header(state: VideoUiState, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val prism = remember { Brush.linearGradient(listOf(StudioIndigoBright, StudioViolet, StudioCyan)) }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.video_eyebrow).uppercase(), style = VisionStudioTheme.typography.eyebrow, color = StudioCyan)
            Text(
                text = "VisionStudio",
                style = MaterialTheme.typography.headlineMedium.copy(brush = prism),
                modifier = Modifier.semantics { heading() },
            )
        }
        EngineChip(
            configured = state.engineConfigured,
            label = state.engineLabel,
            emptyLabel = stringResource(R.string.video_engine_none),
            onClick = onOpenSettings,
            onClickLabel = stringResource(R.string.video_open_settings),
        )
    }
}

@Composable
private fun EmptyStage() {
    GlassPanel(modifier = Modifier.fillMaxWidth(), shape = StudioShapes.Panel) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Movie, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(44.dp))
            Text(stringResource(R.string.video_empty_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.video_empty_body), style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun UnconfiguredStage(onOpenSettings: () -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth(), shape = StudioShapes.Panel) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Cable, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(40.dp))
            Text(stringResource(R.string.video_unconfigured_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.video_unconfigured_body), style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary, textAlign = TextAlign.Center)
            PrismButton(text = stringResource(R.string.video_unconfigured_action), onClick = onOpenSettings)
        }
    }
}

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
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
            contentDescription = stringResource(R.string.video_job_dismiss),
            tint = StudioTextSecondary,
            modifier = Modifier.size(22.dp).tactileClickable(haptic = HapticPattern.Tick, onClick = onDismiss),
        )
    }
}

private val STAGE_GLOW = Brush.linearGradient(listOf(StudioIndigoBright, StudioViolet, StudioCyan))
private val PLAYER_CORNER = 24.dp
private const val STAGE_FADE_MILLIS = 360

@Preview(showBackground = true, backgroundColor = 0xFF0A0B0E, heightDp = 820)
@Composable
private fun VideoEmptyPreview() {
    VisionStudioTheme {
        VideoScreen(state = VideoUiState(engineConfigured = true, engineLabel = "kling-video"), onIntent = {}, onOpenSettings = {})
    }
}
