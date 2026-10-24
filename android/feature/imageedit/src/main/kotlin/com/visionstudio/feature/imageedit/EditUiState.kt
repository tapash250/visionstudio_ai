package com.visionstudio.feature.imageedit

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.visionstudio.core.model.MediaItem
import com.visionstudio.feature.imageedit.mask.BrushSettings
import com.visionstudio.feature.imageedit.mask.MaskHistory
import com.visionstudio.feature.imageedit.mask.MaskStroke

/** The photo being edited, as a hardware-backed bitmap for display. [width]/[height] are the upload dimensions. */
@Immutable
data class SourceUi(val bitmap: ImageBitmap, val width: Int, val height: Int)

@Immutable
sealed interface EditPhase {
    /** No photo chosen yet. */
    data object Empty : EditPhase

    data object Loading : EditPhase

    /** Painting the mask. */
    data object Editing : EditPhase

    data class Rendering(
        val progress: Float?,
        val message: String?,
        val queuePosition: Int?,
        val preview: ImageBitmap?,
    ) : EditPhase

    data class Result(val item: MediaItem) : EditPhase
}

@Immutable
data class EditUiState(
    val engineConfigured: Boolean = false,
    val engineLabel: String = "",
    val phase: EditPhase = EditPhase.Empty,
    val source: SourceUi? = null,
    val history: MaskHistory = MaskHistory.Empty,
    val brush: BrushSettings = BrushSettings(),
    val prompt: String = "",
    val strength: Float = DEFAULT_STRENGTH,
    val error: String? = null,
) {
    val rendering: Boolean get() = phase is EditPhase.Rendering
    val canRepaint: Boolean
        get() = phase == EditPhase.Editing && engineConfigured && prompt.isNotBlank() && !history.isEmpty

    companion object {
        const val DEFAULT_STRENGTH: Float = 0.85f
    }
}

sealed interface EditIntent {
    /** Result of the system Photo Picker; null when the user dismissed it. */
    data class PhotoPicked(val uri: Uri?) : EditIntent

    data object ChangePhoto : EditIntent
    data class SetBrushDiameter(val fraction: Float) : EditIntent
    data class SetErasing(val erase: Boolean) : EditIntent
    data class CommitStroke(val stroke: MaskStroke) : EditIntent
    data object Undo : EditIntent
    data object Redo : EditIntent
    data object ClearMask : EditIntent
    data class EditPrompt(val value: String) : EditIntent
    data class SetStrength(val value: Float) : EditIntent
    data object Repaint : EditIntent
    data object Cancel : EditIntent

    /** Leave the result and keep painting on the same original photo and mask. */
    data object KeepEditing : EditIntent

    /** Make the rendered result the new source photo, clearing the mask. */
    data object UseResultAsSource : EditIntent
    data object DismissError : EditIntent
}
