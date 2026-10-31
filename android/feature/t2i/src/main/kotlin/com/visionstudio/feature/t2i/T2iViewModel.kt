package com.visionstudio.feature.t2i

import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.error.toVisionStudioException
import com.visionstudio.core.data.generation.GenerationEvent
import com.visionstudio.core.data.generation.GenerationRepository
import com.visionstudio.core.data.media.MediaInspector
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.security.SecurePreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class T2iViewModel @Inject constructor(
    private val repository: GenerationRepository,
    private val securePreferences: SecurePreferencesRepository,
    private val inspector: MediaInspector,
) : ViewModel() {

    private val _uiState = MutableStateFlow(T2iUiState())
    val uiState: StateFlow<T2iUiState> = _uiState.asStateFlow()

    private var engineConfig: EngineConfig = EngineConfig.unconfigured(EngineSlot.TEXT_TO_IMAGE)
    private var generation: Job? = null

    init {
        viewModelScope.launch {
            securePreferences.observeEngineConfigs().collect { configs ->
                val config = configs[EngineSlot.TEXT_TO_IMAGE] ?: EngineConfig.unconfigured(EngineSlot.TEXT_TO_IMAGE)
                engineConfig = config
                _uiState.update { it.copy(engine = config.toBadge()) }
            }
        }
        viewModelScope.launch {
            repository.observeRecent(EngineSlot.TEXT_TO_IMAGE).collect { recent ->
                _uiState.update { state ->
                    val phase = state.phase
                    // If the shown item was deleted elsewhere, fall back to the empty stage.
                    val stillThere = phase !is StagePhase.Showing || recent.any { it.id == phase.item.id }
                    val next = when {
                        !stillThere -> StagePhase.Empty
                        // Open on the latest render rather than an empty frame.
                        phase == StagePhase.Empty && recent.isNotEmpty() -> StagePhase.Showing(recent.first(), fresh = false)
                        else -> phase
                    }
                    state.copy(recent = recent, phase = next)
                }
            }
        }
    }

    fun onIntent(intent: T2iIntent) {
        when (intent) {
            is T2iIntent.EditPrompt -> editControls { it.copy(prompt = intent.value.take(MAX_PROMPT)) }
            is T2iIntent.EditNegative -> editControls { it.copy(negativePrompt = intent.value.take(MAX_PROMPT)) }
            is T2iIntent.SelectAspect -> editControls { it.copy(aspectRatio = intent.ratio) }
            is T2iIntent.SetSteps -> editControls { it.copy(steps = intent.steps.coerceIn(GenerationParameters.STEPS_RANGE)) }
            is T2iIntent.SetGuidance -> editControls {
                it.copy(guidance = intent.guidance.coerceIn(GenerationParameters.GUIDANCE_RANGE))
            }
            is T2iIntent.SetRandomSeed -> editControls { it.copy(randomSeed = intent.random) }
            is T2iIntent.EditSeed -> editControls { it.copy(seedText = intent.value.filter(Char::isDigit).take(MAX_SEED_DIGITS)) }
            T2iIntent.ToggleAdvanced -> editControls { it.copy(advancedOpen = !it.advancedOpen) }
            T2iIntent.Generate -> generate()
            T2iIntent.Cancel -> cancel()
            is T2iIntent.Show -> if (!_uiState.value.rendering) {
                _uiState.update { it.copy(phase = StagePhase.Showing(intent.item, fresh = false)) }
            }
            is T2iIntent.Remix -> remix(intent)
        }
    }

    private fun generate() {
        val state = _uiState.value
        if (state.rendering || !state.controls.canSubmit || !state.engine.configured) return
        val controls = state.controls
        val seed = if (controls.randomSeed) Random.nextLong(0, MAX_SEED) else controls.seedText.toLongOrNull()
        val parameters = try {
            GenerationParameters(
                prompt = controls.prompt.trim(),
                negativePrompt = controls.negativePrompt.trim(),
                aspectRatio = controls.aspectRatio,
                steps = controls.steps,
                guidanceScale = controls.guidance,
                seed = seed,
                modelId = engineConfig.modelId.ifBlank { engineConfig.mcpToolName.orEmpty() },
            )
        } catch (invalid: IllegalArgumentException) {
            _uiState.update { it.copy(phase = StagePhase.Failed(invalid.message ?: "Invalid settings", retryable = false)) }
            return
        }

        _uiState.update { it.copy(phase = StagePhase.Rendering(null, null, null, null)) }
        generation = viewModelScope.launch {
            try {
                repository.generate(EngineSlot.TEXT_TO_IMAGE, parameters).collect { event -> onEvent(event) }
            } catch (cancelled: CancellationException) {
                _uiState.update { s -> if (s.rendering) s.copy(phase = restingPhase(s)) else s }
                throw cancelled
            } catch (failure: Exception) {
                val error = failure.toVisionStudioException()
                _uiState.update { it.copy(phase = StagePhase.Failed(error.userMessage(), error.isRetryable)) }
            }
        }
    }

    private suspend fun onEvent(event: GenerationEvent) {
        when (event) {
            is GenerationEvent.Started -> Unit
            is GenerationEvent.Progress -> updateRendering { r ->
                r.copy(
                    // Never let progress run backwards when a provider reports out of order.
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
                state.copy(phase = event.items.firstOrNull()?.let { StagePhase.Showing(it, fresh = true) } ?: StagePhase.Empty)
            }
        }
    }

    private fun cancel() {
        generation?.cancel()
        generation = null
    }

    private fun remix(intent: T2iIntent.Remix) {
        val p = intent.item.parameters
        editControls {
            it.copy(
                prompt = p.prompt,
                negativePrompt = p.negativePrompt,
                aspectRatio = p.aspectRatio,
                steps = p.steps,
                guidance = p.guidanceScale,
                randomSeed = p.seed == null,
                seedText = p.seed?.toString().orEmpty(),
                advancedOpen = true,
            )
        }
    }

    private inline fun editControls(transform: (PromptControls) -> PromptControls) {
        _uiState.update { state ->
            val failed = state.phase is StagePhase.Failed
            state.copy(controls = transform(state.controls), phase = if (failed) restingPhase(state) else state.phase)
        }
    }

    private inline fun updateRendering(crossinline transform: (StagePhase.Rendering) -> StagePhase.Rendering) {
        _uiState.update { state ->
            val phase = state.phase as? StagePhase.Rendering ?: return@update state
            state.copy(phase = transform(phase))
        }
    }

    private fun restingPhase(state: T2iUiState): StagePhase =
        state.recent.firstOrNull()?.let { StagePhase.Showing(it, fresh = false) } ?: StagePhase.Empty

    private fun EngineConfig.toBadge(): EngineBadge = EngineBadge(configured = isConfigured, label = shortLabel)

    private fun VisionStudioException.userMessage(): String = when (this) {
        is VisionStudioException.AuthenticationException -> "The provider rejected your API key. Re-verify it in Settings."
        is VisionStudioException.TimeoutException -> "The provider took too long to answer."
        is VisionStudioException.NetworkException -> "Can't reach the provider. Check your connection."
        is VisionStudioException.ValidationException -> message ?: "Check your engine settings."
        is VisionStudioException.ProviderException -> providerMessage ?: message ?: "The provider reported an error."
        else -> message ?: "Something went wrong."
    }

    private companion object {
        const val MAX_PROMPT = 2_000
        const val MAX_SEED_DIGITS = 10
        const val MAX_SEED = 4_294_967_295L
    }
}
