package com.visionstudio.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.error.toVisionStudioException
import com.visionstudio.core.common.result.Result
import com.visionstudio.core.common.result.getOrNull
import com.visionstudio.core.common.result.getOrThrow
import com.visionstudio.core.model.AuthScheme
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.core.model.RestDialect
import com.visionstudio.core.network.engine.ConnectionProbe
import com.visionstudio.core.network.engine.EngineFactory
import com.visionstudio.core.network.engine.GenerationEngine
import com.visionstudio.core.security.SecurePreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives the three-slot engine configuration screen.
 *
 * Flow: edit draft → Test (probe only) or Verify & Save. Verify & Save probes the draft with
 * the key that would be persisted, and writes to the Keystore-backed store only if the probe
 * passes. Save without verifying is offered after a failed probe.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val securePreferences: SecurePreferencesRepository,
    private val engineFactory: EngineFactory,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var probeJob: Job? = null

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            // Replay of a recovery that happened before this screen opened also counts.
            securePreferences.recoveryEvents.collect {
                _uiState.update { it.copy(showRecoveryNotice = true) }
                load()
            }
        }
        viewModelScope.launch {
            securePreferences.observeEngineConfigs().drop(1).collect { configs ->
                // Keep the persisted flag in sync if another component writes a config.
                _uiState.update { state ->
                    state.copy(
                        drafts = state.drafts.mapValues { (slot, draft) ->
                            draft.copy(persisted = configs[slot]?.isConfigured == true)
                        },
                    )
                }
            }
        }
    }

    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SelectSlot -> _uiState.update { it.copy(selectedSlot = intent.slot, fieldErrors = emptyMap()) }
            is SettingsIntent.SetProtocol -> editDraft { it.copy(protocol = intent.protocol) }
            is SettingsIntent.SetDialect -> editDraft { draft ->
                draft.copy(
                    restDialect = intent.dialect,
                    authScheme = when (intent.dialect) {
                        RestDialect.FAL_QUEUE -> AuthScheme.KEY
                        RestDialect.OPENAI_COMPATIBLE -> AuthScheme.BEARER
                        RestDialect.CUSTOM -> draft.authScheme
                    },
                )
            }
            is SettingsIntent.SetAuthScheme -> editDraft { it.copy(authScheme = intent.scheme) }
            is SettingsIntent.EditField -> editField(intent.field, intent.value)
            SettingsIntent.TestConnection -> probe(persistOnSuccess = false)
            SettingsIntent.VerifyAndSave -> probe(persistOnSuccess = true)
            SettingsIntent.SaveWithoutVerifying -> saveWithoutVerifying()
            SettingsIntent.DismissRecoveryNotice -> _uiState.update { it.copy(showRecoveryNotice = false) }
            SettingsIntent.ConsumeSaved -> _uiState.update { it.copy(justSaved = null) }
        }
    }

    private suspend fun load() {
        val drafts = EngineSlot.entries.associateWith { slot ->
            val config = securePreferences.getEngineConfig(slot).getOrNull() ?: EngineConfig.unconfigured(slot)
            val hasKey = securePreferences.hasApiKey(slot).getOrNull() == true
            SlotDraft.from(config, hasKey)
        }
        _uiState.update { it.copy(loaded = true, drafts = drafts) }
    }

    private fun editField(field: SettingsField, value: String) = editDraft(clearError = field) { draft ->
        when (field) {
            SettingsField.Endpoint -> draft.copy(endpointUrl = value)
            SettingsField.Model -> draft.copy(modelId = value)
            SettingsField.Tool -> draft.copy(mcpToolName = value)
            SettingsField.Header -> draft.copy(customAuthHeader = value)
            SettingsField.ApiKey -> draft.copy(apiKeyInput = value)
        }
    }

    /** Any edit invalidates a previous probe result for the slot and cancels one in flight. */
    private inline fun editDraft(clearError: SettingsField? = null, transform: (SlotDraft) -> SlotDraft) {
        probeJob?.cancel()
        _uiState.update { state ->
            val slot = state.selectedSlot
            state.copy(
                drafts = state.drafts + (slot to transform(state.drafts.getValue(slot)).copy(dirty = true)),
                probes = state.probes + (slot to ProbeUiState.Idle),
                fieldErrors = if (clearError == null) emptyMap() else state.fieldErrors - clearError,
            )
        }
    }

    private fun probe(persistOnSuccess: Boolean) {
        val state = _uiState.value
        if (state.busy) return
        val slot = state.selectedSlot
        val draft = state.draft

        probeJob = viewModelScope.launch {
            setProbe(slot, ProbeUiState.Testing)
            val prepared = prepare(draft) ?: run {
                setProbe(slot, ProbeUiState.Idle)
                return@launch
            }
            val result = runProbe(prepared.engine)
            setProbe(slot, result.toUiState())
            if (persistOnSuccess && result.success) persist(slot, prepared.config, draft)
        }
    }

    private fun saveWithoutVerifying() {
        val state = _uiState.value
        if (state.busy) return
        val draft = state.draft
        viewModelScope.launch {
            val prepared = prepare(draft) ?: return@launch
            withContext(NonCancellable) { prepared.engine.close() }
            persist(draft.slot, prepared.config, draft)
        }
    }

    private class Prepared(val config: EngineConfig, val engine: GenerationEngine)

    /** Validates the draft by building a real engine. Field errors are surfaced inline. */
    private suspend fun prepare(draft: SlotDraft): Prepared? {
        return try {
            val config = draft.toEngineConfig()
            val apiKey = draft.apiKeyInput.trim().ifEmpty {
                if (draft.hasStoredKey) securePreferences.getApiKey(draft.slot).getOrThrow().orEmpty() else ""
            }.ifEmpty { null }
            Prepared(config, engineFactory.create(config, apiKey))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (invalid: VisionStudioException.ValidationException) {
            showFieldError(SettingsField.fromKey(invalid.field) ?: SettingsField.Endpoint, invalid.message.orEmpty())
            null
        } catch (invalid: IllegalArgumentException) {
            showFieldError(SettingsField.Header, invalid.message.orEmpty())
            null
        } catch (failure: Exception) {
            val mapped = failure.toVisionStudioException()
            setProbe(draft.slot, ProbeUiState.Failed("Could not prepare engine", listOfNotNull(mapped.message), null))
            null
        }
    }

    private suspend fun runProbe(engine: GenerationEngine): ConnectionProbe = try {
        withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) { engine.testConnection() }
            ?: ConnectionProbe(false, "Connection timed out", listOf("No answer within ${PROBE_TIMEOUT_MILLIS / 1000}s"))
    } finally {
        withContext(NonCancellable) { engine.close() }
    }

    private suspend fun persist(slot: EngineSlot, config: EngineConfig, draft: SlotDraft) {
        _uiState.update { it.copy(saving = true) }
        val newKey = draft.apiKeyInput.trim()
        val outcome = securePreferences.saveEngineConfig(config).let { saved ->
            if (saved is Result.Success && newKey.isNotEmpty()) securePreferences.setApiKey(slot, newKey) else saved
        }
        _uiState.update { state ->
            when (outcome) {
                is Result.Success -> state.copy(
                    saving = false,
                    justSaved = slot,
                    drafts = state.drafts + (
                        slot to state.drafts.getValue(slot).copy(
                            apiKeyInput = "",
                            hasStoredKey = draft.hasStoredKey || newKey.isNotEmpty(),
                            dirty = false,
                            persisted = true,
                        )
                        ),
                )
                is Result.Error -> state.copy(
                    saving = false,
                    probes = state.probes + (slot to ProbeUiState.Failed("Could not save", listOfNotNull(outcome.exception.message), null)),
                )
                is Result.Loading -> state.copy(saving = false)
            }
        }
    }

    private fun showFieldError(field: SettingsField, message: String) {
        _uiState.update { it.copy(fieldErrors = it.fieldErrors + (field to message)) }
    }

    private fun setProbe(slot: EngineSlot, probe: ProbeUiState) {
        _uiState.update { it.copy(probes = it.probes + (slot to probe)) }
    }

    private fun ConnectionProbe.toUiState(): ProbeUiState =
        if (success) ProbeUiState.Passed(headline, details, latencyMillis) else ProbeUiState.Failed(headline, details, latencyMillis)

    private companion object {
        const val PROBE_TIMEOUT_MILLIS = 30_000L
    }
}

internal val ProviderProtocol.isMcp: Boolean get() = this == ProviderProtocol.MCP
