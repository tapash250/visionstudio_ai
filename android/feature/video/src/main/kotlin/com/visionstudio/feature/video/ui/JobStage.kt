package com.visionstudio.feature.video.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visionstudio.core.data.video.VideoJobStatus
import com.visionstudio.core.designsystem.component.GhostButton
import com.visionstudio.core.designsystem.component.GlassPanel
import com.visionstudio.core.designsystem.component.OrbState
import com.visionstudio.core.designsystem.component.PrismProgressBar
import com.visionstudio.core.designsystem.component.StatusOrb
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioError
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.StudioTextTertiary
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.feature.video.JobUi
import com.visionstudio.feature.video.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The live view of one render: status beacon, a large percentage, a prism progress bar, the
 * prompt, elapsed time, and either Cancel (while running) or Try again / Dismiss (when it ended badly).
 */
@Composable
internal fun JobStage(
    job: JobUi,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = job.status.isActive
    val failed = job.status == VideoJobStatus.FAILED
    val pulse = rememberInfiniteTransition(label = "jobPulse").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
        label = "jobPulseValue",
    )
    val elapsedMillis by produceState(0L, job.id, active) {
        while (active) {
            value = System.currentTimeMillis() - job.createdAtEpochMillis
            delay(ELAPSED_TICK_MILLIS)
        }
    }

    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        shape = StudioShapes.Panel,
        glowing = active,
        glowIntensity = { pulse.value },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusOrb(
                state = when (job.status) {
                    VideoJobStatus.FAILED -> OrbState.Failure
                    VideoJobStatus.CANCELLED -> OrbState.Idle
                    else -> OrbState.Working
                },
                size = 76.dp,
            )

            if (active) {
                AnimatedContent(
                    targetState = job.progress?.let { (it * PERCENT).roundToInt() },
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "jobPercent",
                ) { percent ->
                    Text(
                        text = percent?.let { "$it%" } ?: "···",
                        style = VisionStudioTheme.typography.progressNumeral.copy(brush = NUMERAL_BRUSH),
                    )
                }
                PrismProgressBar(progress = job.progress, modifier = Modifier.padding(horizontal = 12.dp))
            }

            Text(
                text = statusLabel(job),
                style = MaterialTheme.typography.titleMedium,
                color = if (failed) StudioError else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (failed && job.error != null) {
                Text(
                    text = job.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = ERROR_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "“${job.prompt}”",
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextSecondary,
                textAlign = TextAlign.Center,
                maxLines = PROMPT_LINES,
                overflow = TextOverflow.Ellipsis,
            )

            if (active) {
                Text(
                    text = stringResource(R.string.video_job_elapsed, formatElapsed(elapsedMillis)),
                    style = VisionStudioTheme.typography.monoSmall,
                    color = StudioCyan,
                )
                Text(
                    text = stringResource(R.string.video_job_leave_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextTertiary,
                    textAlign = TextAlign.Center,
                )
                GhostButton(stringResource(R.string.video_job_cancel), onCancel, icon = Icons.Rounded.Close)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(stringResource(R.string.video_job_retry), onRetry, icon = Icons.Rounded.Refresh)
                    GhostButton(stringResource(R.string.video_job_dismiss), onDismiss, icon = Icons.Rounded.Delete)
                }
            }
        }
    }
}

@Composable
internal fun statusLabel(job: JobUi): String = when (job.status) {
    VideoJobStatus.QUEUED -> stringResource(R.string.video_job_status_queued)
    VideoJobStatus.SUBMITTING -> stringResource(R.string.video_job_status_submitting)
    VideoJobStatus.POLLING -> job.message ?: stringResource(R.string.video_job_status_polling)
    VideoJobStatus.DOWNLOADING -> stringResource(R.string.video_job_status_downloading)
    VideoJobStatus.FAILED -> stringResource(R.string.video_job_failed)
    VideoJobStatus.CANCELLED -> stringResource(R.string.video_job_cancelled)
    VideoJobStatus.COMPLETED -> ""
}

internal fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis / MILLIS_PER_SECOND).coerceAtLeast(0)
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "%d:%02d".format(minutes, seconds)
}

private val NUMERAL_BRUSH = Brush.linearGradient(listOf(Color.White, StudioCyan))
private const val PULSE_MILLIS = 1_600
private const val ELAPSED_TICK_MILLIS = 1_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val PERCENT = 100f
private const val PROMPT_LINES = 2
private const val ERROR_LINES = 4
