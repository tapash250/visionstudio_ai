package com.visionstudio.feature.settings

import androidx.compose.runtime.Immutable
import com.visionstudio.core.model.AuthScheme
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.core.model.RestDialect

/** Editable, not-yet-persisted form state for one slot. */
@Immutable
data class SlotDraft(
    val slot: EngineSlot,
    val protocol: ProviderProtocol = ProviderProtocol.REST,
    val restDialect: RestDialect = RestDialect.OPENAI_COMPATIBLE,
    val authScheme: AuthScheme = AuthScheme.BEARER,
    val endpointUrl: String = "",
    val modelId: String = "",
    val mcpToolName: String = "",
    val customAuthHeader: String = "",
    /** Only what the user typed this session. Saved keys are never loaded back into memory. */
    val apiKeyInput: String = "",
    val hasStoredKey: Boolean = false,
    /** True when the draft differs from what is persisted. */
    val dirty: Boolean = false,
    /** True once the slot has been saved at least once. */
    val persisted: Boolean = false,
) {
    fun toEngineConfig(): EngineConfig = EngineConfig(
        slot = slot,
        protocol = protocol,
        endpointUrl = endpointUrl.trim(),
        modelId = modelId.trim(),
        restDialect = restDialect,
        authScheme = authScheme,
        customAuthHeader = customAuthHeader.trim().takeIf { authScheme == AuthScheme.CUSTOM_HEADER },
        mcpToolName = mcpToolName.trim().takeIf { protocol == ProviderProtocol.MCP && it.isNotEmpty() },
    )

    companion object {
        fun from(config: EngineConfig, hasStoredKey: Boolean): SlotDraft = SlotDraft(
            slot = config.slot,
            protocol = config.protocol,
            restDialect = config.restDialect,
            authScheme = config.authScheme,
            endpointUrl = config.endpointUrl,
            modelId = config.modelId,
            mcpToolName = config.mcpToolName.orEmpty(),
            customAuthHeader = config.customAuthHeader.orEmpty(),
            hasStoredKey = hasStoredKey,
            persisted = config.isConfigured,
        )
    }
}

@Immutable
sealed interface ProbeUiState {
    data object Idle : ProbeUiState
    data object Testing : ProbeUiState
    data class Passed(val headline: String, val details: List<String>, val latencyMillis: Long?) : ProbeUiState
    data class Failed(val headline: String, val details: List<String>, val latencyMillis: Long?) : ProbeUiState
}

/** Form field identifiers, matching ValidationException.field values from the engine layer. */
enum class SettingsField(val key: String) {
    Endpoint("endpointUrl"),
    Model("modelId"),
    Tool("mcpToolName"),
    Header("customAuthHeader"),
    ApiKey("apiKey"),
    ;

    companion object {
        fun fromKey(key: String?): SettingsField? = entries.firstOrNull { it.key == key }
    }
}

@Immutable
data class SettingsUiState(
    val loaded: Boolean = false,
    val selectedSlot: EngineSlot = EngineSlot.TEXT_TO_IMAGE,
    val drafts: Map<EngineSlot, SlotDraft> = EngineSlot.entries.associateWith { SlotDraft(it) },
    val probes: Map<EngineSlot, ProbeUiState> = EngineSlot.entries.associateWith { ProbeUiState.Idle },
    val fieldErrors: Map<SettingsField, String> = emptyMap(),
    val saving: Boolean = false,
    /** Slot just saved; drives a transient confirmation. */
    val justSaved: EngineSlot? = null,
    val showRecoveryNotice: Boolean = false,
) {
    val draft: SlotDraft get() = drafts.getValue(selectedSlot)
    val probe: ProbeUiState get() = probes.getValue(selectedSlot)
    val busy: Boolean get() = saving || probe is ProbeUiState.Testing
}

sealed interface SettingsIntent {
    data class SelectSlot(val slot: EngineSlot) : SettingsIntent
    data class SetProtocol(val protocol: ProviderProtocol) : SettingsIntent
    data class SetDialect(val dialect: RestDialect) : SettingsIntent
    data class SetAuthScheme(val scheme: AuthScheme) : SettingsIntent
    data class EditField(val field: SettingsField, val value: String) : SettingsIntent
    data object TestConnection : SettingsIntent
    data object VerifyAndSave : SettingsIntent
    data object SaveWithoutVerifying : SettingsIntent
    data object DismissRecoveryNotice : SettingsIntent
    data object ConsumeSaved : SettingsIntent
}
