package com.visionstudio.feature.video

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.error.toVisionStudioException
import com.visionstudio.core.data.generation.GenerationRepository
import com.visionstudio.core.data.media.MediaInspector
import com.visionstudio.core.data.media.SourceImageLoader
import com.visionstudio.core.data.video.VideoJob
import com.visionstudio.core.data.video.VideoJobRepository
import com.visionstudio.core.data.video.VideoJobStatus
import com.visionstudio.core.model.AspectRatio
import com.visionstudio.core.model.CameraMotion
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.network.engine.BinaryPayload
import com.visionstudio.core.security.SecurePreferencesRepository
import com.visionstudio.feature.video.work.VideoJobLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

/**
 * Drives the Motion studio. Submitting hands the work to [VideoJobLauncher] and returns at
 * once: the render itself runs in WorkManager, so it survives leaving the screen or the app.
 * This ViewModel only *observes* the persisted jobs, so after a process restart the screen
 * simply picks the running job back up.
 */
@HiltViewModel
class VideoViewModel @Inject constructor(
    private val launcher: VideoJobLauncher,
    private val jobs: VideoJobRepository,
    private val generations: GenerationRepository,
    private val securePreferences: SecurePreferencesRepository,
    private val loader: SourceImageLoader,
    private val inspector: MediaInspector,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState: StateFlow<VideoUiState> = _uiState.asStateFlow()

    private var engineConfig: EngineConfig = EngineConfig.unconfigured(EngineSlot.VIDEO_STUDIO)
    private var photoPayload: BinaryPayload? = null
    private var photoReference: String? = null
    private var displayedPhoto: Bitmap? = null
    private var photoJob: Job? = null

    init {
        viewModelScope.launch {
            securePreferences.observeEngineConfigs().collect { configs ->
                val config = configs[EngineSlot.VIDEO_STUDIO] ?: EngineConfig.unconfigured(EngineSlot.VIDEO_STUDIO)
                engineConfig = config
                _uiState.update { it.copy(engineConfigured = config.isConfigured, engineLabel = config.shortLabel) }
            }
        }
        viewModelScope.launch {
            generations.observeRecent(EngineSlot.VIDEO_STUDIO).collect { recent ->
                _uiState.update { it.copy(recent = recent) }
            }
        }
        viewModelScope.launch {
            jobs.observeJobs().collect { all -> onJobsChanged(all) }
        }
    }

    fun onIntent(intent: VideoIntent) {
        when (intent) {
            is VideoIntent.SetMode -> edit { it.copy(mode = intent.mode) }
            is VideoIntent.EditPrompt -> edit { it.copy(prompt = intent.value.take(MAX_PROMPT)) }
            is VideoIntent.SetAspect -> edit { it.copy(aspectRatio = intent.ratio) }
            is VideoIntent.SetMotion -> edit { it.copy(motion = intent.motion) }
            is VideoIntent.SetDuration -> edit { it.copy(durationSeconds = intent.seconds) }
            VideoIntent.ToggleAdvanced -> edit { it.copy(advancedOpen = !it.advancedOpen) }
            is VideoIntent.PhotoPicked -> intent.uri?.let { loadPhoto(it.toString(), it) }
            VideoIntent.RemovePhoto -> clearPhoto()
            VideoIntent.Generate -> generate()
            is VideoIntent.FocusJob -> _uiState.update { s -> if (s.jobs.any { it.id == intent.id }) s.copy(focus = Focus.Job(intent.id)) else s }
            is VideoIntent.FocusItem -> _uiState.update { s -> if (s.recent.any { it.id == intent.id }) s.copy(focus = Focus.Item(intent.id)) else s }
            is VideoIntent.CancelJob -> viewModelScope.launch { launcher.cancel(intent.id) }
            is VideoIntent.RetryJob -> retry(intent.id)
            is VideoIntent.DismissJob -> viewModelScope.launch { launcher.dismiss(intent.id) }
            VideoIntent.DismissNotice -> _uiState.update { it.copy(notice = null) }
        }
    }

    private inline fun edit(transform: (VideoControls) -> VideoControls) {
        _uiState.update { it.copy(controls = transform(it.controls), notice = null) }
    }

    private fun onJobsChanged(all: List<VideoJob>) {
        _uiState.update { state ->
            val visible = all.filter { it.status != VideoJobStatus.COMPLETED }
            var focus = state.focus
            val focusedJob = (focus as? Focus.Job)?.let { f -> all.firstOrNull { it.id == f.id } }
            when {
                // The job being watched finished: show the video it produced.
                focusedJob != null && focusedJob.status == VideoJobStatus.COMPLETED && focusedJob.resultItemId != null ->
                    focus = Focus.Item(focusedJob.resultItemId!!)
                // The job being watched was dismissed elsewhere.
                focus is Focus.Job && focusedJob == null -> focus = Focus.None
            }
            // Reopening the app mid-render: pick the running job back up.
            if (focus == Focus.None) {
                visible.firstOrNull { it.status.isActive }?.let { focus = Focus.Job(it.id) }
            }
            state.copy(jobs = visible.map { it.toUi() }, focus = focus)
        }
    }

    private fun generate() {
        val state = _uiState.value
        if (!state.canGenerate) return
        val controls = state.controls
        val photo = state.photo.takeIf { controls.mode == VideoMode.IMAGE_TO_VIDEO }
        val payload = photoPayload.takeIf { photo != null }

        val parameters = try {
            GenerationParameters(
                prompt = controls.prompt.trim(),
                // Image-to-video keeps the photo's shape; text-to-video uses the chosen frame.
                aspectRatio = photo?.let { AspectRatio.closestTo(it.width, it.height) } ?: controls.aspectRatio,
                seed = Random.nextLong(0, MAX_SEED),
                modelId = engineConfig.modelId.ifBlank { engineConfig.mcpToolName.orEmpty() },
                sourceImageUri = if (photo != null) photoReference else null,
                cameraMotion = controls.motion.takeIf { it != CameraMotion.STATIC },
                durationSeconds = controls.durationSeconds,
            )
        } catch (invalid: IllegalArgumentException) {
            _uiState.update { it.copy(notice = invalid.message ?: "Check your settings.") }
            return
        }

        viewModelScope.launch {
            try {
                val id = launcher.launch(parameters, payload)
                _uiState.update { it.copy(focus = Focus.Job(id), notice = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _uiState.update { it.copy(notice = failure.toVisionStudioException().readable()) }
            }
        }
    }

    private fun retry(id: String) {
        viewModelScope.launch {
            if (launcher.retry(id)) {
                _uiState.update { it.copy(focus = Focus.Job(id), notice = null) }
            } else {
                _uiState.update { it.copy(notice = "This render can't be retried. Start a new one.") }
            }
        }
    }

    private fun loadPhoto(reference: String, uri: android.net.Uri) {
        photoJob?.cancel()
        _uiState.update { it.copy(photoLoading = true, notice = null) }
        photoJob = viewModelScope.launch {
            try {
                val normalized = loader.load(uri, maxEdge = PHOTO_MAX_EDGE)
                val bitmap = inspector.decodePreview(normalized.payload.bytes)
                    ?: throw VisionStudioException.StorageException("Couldn't display the selected image")
                val previous = displayedPhoto
                displayedPhoto = bitmap
                photoPayload = normalized.payload
                photoReference = reference
                _uiState.update {
                    it.copy(
                        photo = PickedPhoto(bitmap.asImageBitmap(), normalized.width, normalized.height),
                        photoLoading = false,
                        controls = it.controls.copy(mode = VideoMode.IMAGE_TO_VIDEO),
                    )
                }
                previous?.let(::retire)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _uiState.update { it.copy(photoLoading = false, notice = failure.toVisionStudioException().readable()) }
            }
        }
    }

    private fun clearPhoto() {
        photoJob?.cancel()
        val previous = displayedPhoto
        displayedPhoto = null
        photoPayload = null
        photoReference = null
        _uiState.update { it.copy(photo = null, photoLoading = false) }
        previous?.let(::retire)
    }

    /** Frees a replaced bitmap after Compose has certainly stopped drawing it. */
    private fun retire(bitmap: Bitmap) {
        viewModelScope.launch {
            delay(RETIRE_DELAY_MILLIS)
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    override fun onCleared() {
        displayedPhoto?.takeIf { !it.isRecycled }?.recycle()
        displayedPhoto = null
        super.onCleared()
    }

    private fun VideoJob.toUi() = JobUi(
        id = id,
        status = status,
        prompt = parameters.prompt,
        progress = progress,
        message = message,
        error = error,
        createdAtEpochMillis = createdAtEpochMillis,
        fromPhoto = hasSourceImage,
    )

    private fun VisionStudioException.readable(): String = when (this) {
        is VisionStudioException.AuthenticationException -> "The provider rejected your API key. Re-verify it in the Engine Room."
        is VisionStudioException.NetworkException -> "Can't reach the provider. Check your connection."
        else -> message ?: "Something went wrong."
    }

    private companion object {
        const val MAX_PROMPT = 2_000
        const val MAX_SEED = 4_294_967_295L
        const val PHOTO_MAX_EDGE = 1280
        const val RETIRE_DELAY_MILLIS = 2_000L
    }
}
