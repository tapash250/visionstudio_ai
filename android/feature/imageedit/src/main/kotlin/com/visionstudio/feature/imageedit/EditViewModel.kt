package com.visionstudio.feature.imageedit

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.error.toVisionStudioException
import com.visionstudio.core.data.generation.GenerationEvent
import com.visionstudio.core.data.generation.GenerationRepository
import com.visionstudio.core.data.media.MediaInspector
import com.visionstudio.core.data.media.SourceImageLoader
import com.visionstudio.core.model.AspectRatio
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.network.engine.BinaryPayload
import com.visionstudio.core.security.SecurePreferencesRepository
import com.visionstudio.feature.imageedit.mask.BrushSettings
import com.visionstudio.feature.imageedit.mask.MaskHistory
import com.visionstudio.feature.imageedit.mask.MaskRasterizer
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

@HiltViewModel
class EditViewModel @Inject constructor(
    private val repository: GenerationRepository,
    private val securePreferences: SecurePreferencesRepository,
    private val loader: SourceImageLoader,
    private val inspector: MediaInspector,
    private val rasterizer: MaskRasterizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditUiState())
    val uiState: StateFlow<EditUiState> = _uiState.asStateFlow()

    private var engineConfig: EngineConfig = EngineConfig.unconfigured(EngineSlot.IMAGE_EDIT)
    private var sourcePayload: BinaryPayload? = null
    private var sourceReference: String? = null
    private var displayedBitmap: Bitmap? = null
    private var loadJob: Job? = null
    private var generation: Job? = null

    init {
        viewModelScope.launch {
            securePreferences.observeEngineConfigs().collect { configs ->
                val config = configs[EngineSlot.IMAGE_EDIT] ?: EngineConfig.unconfigured(EngineSlot.IMAGE_EDIT)
                engineConfig = config
                _uiState.update { it.copy(engineConfigured = config.isConfigured, engineLabel = config.shortLabel) }
            }
        }
    }

    fun onIntent(intent: EditIntent) {
        when (intent) {
            is EditIntent.PhotoPicked -> intent.uri?.let(::loadSource)
            EditIntent.ChangePhoto -> changePhoto()
            is EditIntent.SetBrushDiameter -> _uiState.update {
                it.copy(
                    brush = it.brush.copy(
                        diameterFraction = intent.fraction.coerceIn(BrushSettings.MIN_DIAMETER, BrushSettings.MAX_DIAMETER),
                    ),
                )
            }
            is EditIntent.SetErasing -> _uiState.update { it.copy(brush = it.brush.copy(erase = intent.erase)) }
            is EditIntent.CommitStroke -> editMask { it.commit(intent.stroke) }
            EditIntent.Undo -> editMask { it.undo() }
            EditIntent.Redo -> editMask { it.redo() }
            EditIntent.ClearMask -> editMask { it.clear() }
            is EditIntent.EditPrompt -> _uiState.update { it.copy(prompt = intent.value.take(MAX_PROMPT), error = null) }
            is EditIntent.SetStrength -> _uiState.update { it.copy(strength = intent.value.coerceIn(0f, 1f)) }
            EditIntent.Repaint -> repaint()
            EditIntent.Cancel -> {
                generation?.cancel()
                generation = null
            }
            EditIntent.KeepEditing -> _uiState.update { if (it.phase is EditPhase.Result) it.copy(phase = EditPhase.Editing) else it }
            EditIntent.UseResultAsSource -> (_uiState.value.phase as? EditPhase.Result)?.let { loadSource(Uri.parse(it.item.localUri)) }
            EditIntent.DismissError -> _uiState.update { it.copy(error = null) }
        }
    }

    private fun editMask(transform: (MaskHistory) -> MaskHistory) {
        _uiState.update { state ->
            if (state.phase != EditPhase.Editing) state else state.copy(history = transform(state.history), error = null)
        }
    }

    private fun changePhoto() {
        if (_uiState.value.rendering) return
        _uiState.update { it.copy(phase = EditPhase.Empty, error = null) }
    }

    private fun loadSource(uri: Uri) {
        if (_uiState.value.rendering) return
        loadJob?.cancel()
        val previousPhase = _uiState.value.phase
        _uiState.update { it.copy(phase = EditPhase.Loading, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val normalized = loader.load(uri)
                val bitmap = inspector.decodePreview(normalized.payload.bytes)
                    ?: throw VisionStudioException.StorageException("Couldn't display the selected image")
                val previous = displayedBitmap
                displayedBitmap = bitmap
                sourcePayload = normalized.payload
                sourceReference = uri.toString()
                _uiState.update {
                    it.copy(
                        phase = EditPhase.Editing,
                        source = SourceUi(bitmap.asImageBitmap(), normalized.width, normalized.height),
                        history = MaskHistory.Empty,
                    )
                }
                previous?.let(::retire)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val restored = if (_uiState.value.source != null && previousPhase != EditPhase.Loading) previousPhase else EditPhase.Empty
                _uiState.update { it.copy(phase = restored, error = failure.toVisionStudioException().readable()) }
            }
        }
    }

    private fun repaint() {
        val state = _uiState.value
        val source = state.source
        val payload = sourcePayload
        if (!state.canRepaint || source == null || payload == null) return
        val seed = Random.nextLong(0, MAX_SEED)

        _uiState.update { it.copy(phase = EditPhase.Rendering(null, null, null, null), error = null) }
        generation = viewModelScope.launch {
            try {
                val mask = rasterizer.rasterize(state.history.strokes, source.width, source.height)
                if (mask.isEmpty) {
                    _uiState.update {
                        it.copy(phase = EditPhase.Editing, error = "Nothing is selected. Paint over the area you want to change.")
                    }
                    return@launch
                }
                val parameters = GenerationParameters(
                    prompt = state.prompt.trim(),
                    aspectRatio = AspectRatio.closestTo(source.width, source.height),
                    seed = seed,
                    modelId = engineConfig.modelId.ifBlank { engineConfig.mcpToolName.orEmpty() },
                    sourceImageUri = sourceReference,
                    strength = state.strength,
                )
                repository.generate(EngineSlot.IMAGE_EDIT, parameters, sourceImage = payload, mask = mask.png)
                    .collect { event -> onEvent(event) }
            } catch (cancelled: CancellationException) {
                _uiState.update { if (it.rendering) it.copy(phase = EditPhase.Editing) else it }
                throw cancelled
            } catch (failure: Exception) {
                _uiState.update { it.copy(phase = EditPhase.Editing, error = failure.toVisionStudioException().readable()) }
            }
        }
    }

    private suspend fun onEvent(event: GenerationEvent) {
        when (event) {
            is GenerationEvent.Started -> Unit
            is GenerationEvent.Progress -> updateRendering { r ->
                r.copy(
                    progress = event.fraction?.let { f -> maxOf(f, r.progress ?: 0f) } ?: r.progress,
                    message = event.message ?: r.message,
                    queuePosition = event.queuePosition,
                )
            }
            is GenerationEvent.Preview -> {
                val bitmap = inspector.decodePreview(event.payload.bytes) ?: return
                updateRendering { it.copy(preview = bitmap.asImageBitmap()) }
            }
            is GenerationEvent.Completed -> _uiState.update { state ->
                event.items.firstOrNull()?.let { state.copy(phase = EditPhase.Result(it)) }
                    ?: state.copy(phase = EditPhase.Editing)
            }
        }
    }

    private inline fun updateRendering(crossinline transform: (EditPhase.Rendering) -> EditPhase.Rendering) {
        _uiState.update { state ->
            val phase = state.phase as? EditPhase.Rendering ?: return@update state
            state.copy(phase = transform(phase))
        }
    }

    /**
     * Frees a replaced display bitmap after a grace period. Compose may still be drawing it for
     * a frame or two after the state flips, and drawing a recycled bitmap throws.
     */
    private fun retire(bitmap: Bitmap) {
        viewModelScope.launch {
            delay(RETIRE_DELAY_MILLIS)
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    override fun onCleared() {
        displayedBitmap?.takeIf { !it.isRecycled }?.recycle()
        displayedBitmap = null
        super.onCleared()
    }

    private fun VisionStudioException.readable(): String = when (this) {
        is VisionStudioException.AuthenticationException -> "The provider rejected your API key. Re-verify it in Settings."
        is VisionStudioException.TimeoutException -> "The provider took too long to answer."
        is VisionStudioException.NetworkException -> "Can't reach the provider. Check your connection."
        is VisionStudioException.ProviderException -> providerMessage ?: message ?: "The provider reported an error."
        else -> message ?: "Something went wrong."
    }

    private companion object {
        const val MAX_PROMPT = 2_000
        const val MAX_SEED = 4_294_967_295L
        const val RETIRE_DELAY_MILLIS = 2_000L
    }
}
