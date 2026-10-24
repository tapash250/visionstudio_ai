package com.visionstudio.feature.video.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.visionstudio.core.data.video.VideoJobStatus
import com.visionstudio.core.designsystem.component.BlurHashImage
import com.visionstudio.core.designsystem.component.PrismProgressBar
import com.visionstudio.core.designsystem.component.SectionHeader
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.modifier.frostedGlass
import com.visionstudio.core.designsystem.modifier.glowingBorder
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioError
import com.visionstudio.core.designsystem.theme.StudioIndigoBright
import com.visionstudio.core.designsystem.theme.StudioOnyx
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.core.model.MediaItem
import com.visionstudio.feature.video.Focus
import com.visionstudio.feature.video.JobUi
import com.visionstudio.feature.video.R
import com.visionstudio.feature.video.VideoUiState
import java.util.Locale

/** Running/failed renders first, then finished videos. Tapping a tile focuses it on the stage. */
@Composable
internal fun RendersStrip(
    state: VideoUiState,
    onFocusJob: (String) -> Unit,
    onFocusItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(if (state.jobs.isNotEmpty()) R.string.video_jobs else R.string.video_recent))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)) {
            items(state.jobs, key = { "job-${it.id}" }) { job ->
                JobTile(job = job, selected = (state.focus as? Focus.Job)?.id == job.id, onClick = { onFocusJob(job.id) })
            }
            items(state.recent, key = { "item-${it.id}" }) { item ->
                VideoTile(item = item, selected = (state.focus as? Focus.Item)?.id == item.id, onClick = { onFocusItem(item.id) })
            }
        }
    }
}

@Composable
private fun JobTile(job: JobUi, selected: Boolean, onClick: () -> Unit) {
    val failed = job.status == VideoJobStatus.FAILED
    val description = job.prompt
    Column(
        modifier = Modifier
            .size(width = 96.dp, height = 62.dp)
            .tileChrome(selected, if (failed) StudioError else StudioCyan)
            .semantics { contentDescription = description }
            .tactileClickable(haptic = HapticPattern.Tick, onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (failed) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = StudioError, modifier = Modifier.size(18.dp))
        } else {
            Text(
                text = job.progress?.let { "${(it * 100).toInt()}%" } ?: "···",
                style = VisionStudioTheme.typography.monoSmall,
                color = StudioCyan,
            )
            PrismProgressBar(progress = job.progress, height = 4.dp)
        }
    }
}

@Composable
private fun VideoTile(item: MediaItem, selected: Boolean, onClick: () -> Unit) {
    val description = item.parameters.prompt
    Box(
        modifier = Modifier
            .size(width = 96.dp, height = 62.dp)
            .tileChrome(selected, StudioIndigoBright)
            .clip(StudioShapes.Control)
            .semantics { contentDescription = description }
            .tactileClickable(haptic = HapticPattern.Tick, onClick = onClick),
    ) {
        BlurHashImage(hash = item.blurHash, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(SCRIM))
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(26.dp))
        item.durationMillis?.let { millis ->
            Text(
                text = String.format(Locale.US, "%d:%02d", millis / 60_000, (millis / 1_000) % 60),
                style = VisionStudioTheme.typography.monoSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
            )
        }
    }
}

private fun Modifier.tileChrome(selected: Boolean, accent: Color): Modifier = this
    .glowingBorder(
        brush = SolidColor(accent),
        shape = StudioShapes.Control,
        width = if (selected) 1.5.dp else 0.dp,
        glowRadius = 6.dp,
        intensity = { if (selected) 1f else 0f },
    )
    .frostedGlass(shape = StudioShapes.Control)

private val SCRIM = StudioOnyx.copy(alpha = 0.35f)
