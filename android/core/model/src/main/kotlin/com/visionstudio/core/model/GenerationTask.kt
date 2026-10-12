package com.visionstudio.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Camera movement directives for the Video Studio. */
@Serializable
enum class CameraMotion(val displayName: String) {
    STATIC("Static"),
    PAN_LEFT("Pan Left"),
    PAN_RIGHT("Pan Right"),
    TILT_UP("Tilt Up"),
    TILT_DOWN("Tilt Down"),
    ZOOM_IN("Zoom In"),
    ZOOM_OUT("Zoom Out"),
    DOLLY_FORWARD("Dolly Forward"),
    ORBIT("Orbit"),
}

/** User-tunable inputs shared by every generation request. */
@Serializable
data class GenerationParameters(
    val prompt: String,
    val negativePrompt: String = "",
    val aspectRatio: AspectRatio = AspectRatio.SQUARE,
    val steps: Int = DEFAULT_STEPS,
    val guidanceScale: Float = DEFAULT_GUIDANCE,
    val seed: Long? = null,
    val modelId: String,
    val sourceImageUri: String? = null,
    val maskImageUri: String? = null,
    val strength: Float? = null,
    val cameraMotion: CameraMotion? = null,
    val durationSeconds: Int? = null,
) {
    init {
        require(steps in STEPS_RANGE) { "steps must be in $STEPS_RANGE, was $steps" }
        require(guidanceScale in GUIDANCE_RANGE) {
            "guidanceScale must be in $GUIDANCE_RANGE, was $guidanceScale"
        }
        strength?.let { require(it in 0f..1f) { "strength must be in 0..1, was $it" } }
        durationSeconds?.let { require(it > 0) { "durationSeconds must be positive, was $it" } }
        require(maskImageUri == null || sourceImageUri != null) {
            "maskImageUri requires a sourceImageUri"
        }
    }

    companion object {
        const val DEFAULT_STEPS: Int = 30
        const val DEFAULT_GUIDANCE: Float = 7.0f
        val STEPS_RANGE: IntRange = 1..150
        val GUIDANCE_RANGE: ClosedFloatingPointRange<Float> = 0f..30f
    }
}

/** Lifecycle of a [GenerationTask]. */
@Serializable
sealed interface GenerationStatus {
    val isTerminal: Boolean

    @Serializable
    @SerialName("queued")
    data object Queued : GenerationStatus {
        override val isTerminal: Boolean get() = false
    }

    /** [progress] is a fraction in 0..1; [remoteJobId] is set for queue/poll based providers. */
    @Serializable
    @SerialName("running")
    data class Running(
        val progress: Float,
        val remoteJobId: String? = null,
        val previewUri: String? = null,
    ) : GenerationStatus {
        init {
            require(progress in 0f..1f) { "progress must be in 0..1, was $progress" }
        }

        override val isTerminal: Boolean get() = false
    }

    @Serializable
    @SerialName("succeeded")
    data class Succeeded(val outputs: List<MediaItem>) : GenerationStatus {
        init {
            require(outputs.isNotEmpty()) { "A successful generation must produce at least one output" }
        }

        override val isTerminal: Boolean get() = true
    }

    @Serializable
    @SerialName("failed")
    data class Failed(val reason: String, val retryable: Boolean) : GenerationStatus {
        override val isTerminal: Boolean get() = true
    }

    @Serializable
    @SerialName("cancelled")
    data object Cancelled : GenerationStatus {
        override val isTerminal: Boolean get() = true
    }
}

/** A single request against an [EngineSlot] and its current [status]. */
@Serializable
data class GenerationTask(
    val id: String,
    val slot: EngineSlot,
    val protocol: ProviderProtocol,
    val parameters: GenerationParameters,
    val status: GenerationStatus = GenerationStatus.Queued,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "updatedAt ($updatedAtEpochMillis) precedes createdAt ($createdAtEpochMillis)"
        }
        if (slot == EngineSlot.IMAGE_EDIT) {
            require(parameters.sourceImageUri != null) { "IMAGE_EDIT tasks require a source image" }
        }
    }

    /** Returns a copy transitioned to [newStatus]; terminal states cannot be left. */
    fun transitionTo(newStatus: GenerationStatus, atEpochMillis: Long): GenerationTask {
        check(!status.isTerminal) { "Task $id is already terminal ($status)" }
        return copy(status = newStatus, updatedAtEpochMillis = maxOf(atEpochMillis, createdAtEpochMillis))
    }
}
