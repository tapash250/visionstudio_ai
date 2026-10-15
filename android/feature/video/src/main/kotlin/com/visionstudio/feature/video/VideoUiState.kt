package com.visionstudio.feature.video

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.visionstudio.core.data.video.VideoJobStatus
import com.visionstudio.core.model.AspectRatio
import com.visionstudio.core.model.CameraMotion
import com.visionstudio.core.model.MediaItem

enum class VideoMode { TEXT_TO_VIDEO, IMAGE_TO_VIDEO }

@Immutable
data class VideoControls(
    val mode: VideoMode = VideoMode.TEXT_TO_VIDEO,
    val prompt: String = "",
    val aspectRatio: AspectRatio = AspectRatio.LANDSCAPE_16_9,
    val motion: CameraMotion = CameraMotion.STATIC,
    val durationSeconds: Int = DEFAULT_DURATION,
    val advancedOpen: Boolean = false,
) {
    companion object {
        const val DEFAULT_DURATION: Int = 5
        val DURATIONS: List<Int> = listOf(3, 5, 8, 10)

        /** Frames offered for text-to-video. Image-to-video follows the photo instead. */
        val ASPECTS: List<AspectRatio> = listOf(
            AspectRatio.LANDSCAPE_16_9,
            AspectRatio.PORTRAIT_9_16,
            AspectRatio.SQUARE,
            AspectRatio.CINEMATIC_21_9,
        )
    }
}

/** The first frame chosen for image-to-video, as a hardware bitmap for display. */
@Immutable
data class PickedPhoto(val bitmap: ImageBitmap, val width: Int, val height: Int)

@Immutable
data class JobUi(
    val id: String,
    val status: VideoJobStatus,
    val prompt: String,
    val progress: Float?,
    val message: String?,
    val error: String?,
    val createdAtEpochMillis: Long,
    val fromPhoto: Boolean,
)

/** What the main stage is showing. */
@Immutable
sealed interface Focus {
    data object None : Focus
    data class Job(val id: String) : Focus
    data class Item(val id: String) : Focus
}

@Immutable
data class VideoUiState(
    val engineConfigured: Boolean = false,
    val engineLabel: String = "",
    val controls: VideoControls = VideoControls(),
    val photo: PickedPhoto? = null,
    val photoLoading: Boolean = false,
    /** Every job that has not completed. Finished videos live in [recent]. */
    val jobs: List<JobUi> = emptyList(),
    val recent: List<MediaItem> = emptyList(),
    val focus: Focus = Focus.None,
    val notice: String? = null,
) {
    val focusedJob: JobUi? get() = (focus as? Focus.Job)?.let { f -> jobs.firstOrNull { it.id == f.id } }
    val focusedItem: MediaItem? get() = (focus as? Focus.Item)?.let { f -> recent.firstOrNull { it.id == f.id } }
    val activeJobs: Int get() = jobs.count { it.status.isActive }

    val canGenerate: Boolean
        get() = engineConfigured &&
            controls.prompt.isNotBlank() &&
            (controls.mode == VideoMode.TEXT_TO_VIDEO || photo != null) &&
            !photoLoading
}

sealed interface VideoIntent {
    data class SetMode(val mode: VideoMode) : VideoIntent
    data class EditPrompt(val value: String) : VideoIntent
    data class SetAspect(val ratio: AspectRatio) : VideoIntent
    data class SetMotion(val motion: CameraMotion) : VideoIntent
    data class SetDuration(val seconds: Int) : VideoIntent
    data object ToggleAdvanced : VideoIntent

    /** Result of the system Photo Picker; null when dismissed. */
    data class PhotoPicked(val uri: Uri?) : VideoIntent
    data object RemovePhoto : VideoIntent

    data object Generate : VideoIntent
    data class FocusJob(val id: String) : VideoIntent
    data class FocusItem(val id: String) : VideoIntent
    data class CancelJob(val id: String) : VideoIntent
    data class RetryJob(val id: String) : VideoIntent
    data class DismissJob(val id: String) : VideoIntent
    data object DismissNotice : VideoIntent
}
