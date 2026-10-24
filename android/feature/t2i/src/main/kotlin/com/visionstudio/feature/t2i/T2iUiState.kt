package com.visionstudio.feature.t2i

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.visionstudio.core.model.AspectRatio
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.MediaItem

@Immutable
data class PromptControls(
    val prompt: String = "",
    val negativePrompt: String = "",
    val aspectRatio: AspectRatio = AspectRatio.SQUARE,
    val steps: Int = GenerationParameters.DEFAULT_STEPS,
    val guidance: Float = GenerationParameters.DEFAULT_GUIDANCE,
    val randomSeed: Boolean = true,
    val seedText: String = "",
    val advancedOpen: Boolean = false,
) {
    val canSubmit: Boolean get() = prompt.isNotBlank()
}

@Immutable
data class EngineBadge(val configured: Boolean, val label: String)

/** What the stage is showing. */
@Immutable
sealed interface StagePhase {
    data object Empty : StagePhase

    /** [preview] is the latest streamed low-resolution render (hardware bitmap). */
    data class Rendering(
        val progress: Float?,
        val message: String?,
        val queuePosition: Int?,
        val preview: ImageBitmap?,
    ) : StagePhase

    data class Showing(val item: MediaItem, val fresh: Boolean) : StagePhase

    data class Failed(val message: String, val retryable: Boolean) : StagePhase
}

@Immutable
data class T2iUiState(
    val controls: PromptControls = PromptControls(),
    val engine: EngineBadge = EngineBadge(configured = false, label = ""),
    val phase: StagePhase = StagePhase.Empty,
    val recent: List<MediaItem> = emptyList(),
) {
    val rendering: Boolean get() = phase is StagePhase.Rendering
    val stageRatio: Float
        get() = when (val p = phase) {
            is StagePhase.Showing -> p.item.width.toFloat() / p.item.height
            else -> controls.aspectRatio.ratio
        }
}

sealed interface T2iIntent {
    data class EditPrompt(val value: String) : T2iIntent
    data class EditNegative(val value: String) : T2iIntent
    data class SelectAspect(val ratio: AspectRatio) : T2iIntent
    data class SetSteps(val steps: Int) : T2iIntent
    data class SetGuidance(val guidance: Float) : T2iIntent
    data class SetRandomSeed(val random: Boolean) : T2iIntent
    data class EditSeed(val value: String) : T2iIntent
    data object ToggleAdvanced : T2iIntent
    data object Generate : T2iIntent
    data object Cancel : T2iIntent
    data class Show(val item: MediaItem) : T2iIntent

    /** Restores prompt and settings from a saved render, pinning its seed. */
    data class Remix(val item: MediaItem) : T2iIntent
}
