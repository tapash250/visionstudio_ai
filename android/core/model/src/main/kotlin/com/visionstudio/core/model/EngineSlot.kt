package com.visionstudio.core.model

import kotlinx.serialization.Serializable

/** The three independently configurable engine slots exposed in Settings. */
@Serializable
enum class EngineSlot(
    val displayName: String,
    val outputType: MediaType,
    val acceptsSourceImage: Boolean,
) {
    TEXT_TO_IMAGE("Text to Image", MediaType.IMAGE, acceptsSourceImage = false),
    IMAGE_EDIT("Image Edit", MediaType.IMAGE, acceptsSourceImage = true),
    VIDEO_STUDIO("Video Studio", MediaType.VIDEO, acceptsSourceImage = true),
}

/**
 * Non-secret configuration of a single [EngineSlot]. API keys are deliberately excluded and
 * stored separately in the hardware-backed secure store, keyed by [slot].
 */
@Serializable
data class EngineConfig(
    val slot: EngineSlot,
    val protocol: ProviderProtocol,
    val endpointUrl: String,
    val modelId: String,
    val restDialect: RestDialect = RestDialect.OPENAI_COMPATIBLE,
    val authScheme: AuthScheme = AuthScheme.BEARER,
    val customAuthHeader: String? = null,
    val mcpToolName: String? = null,
    val requestTimeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
) {
    init {
        require(requestTimeoutSeconds > 0) { "requestTimeoutSeconds must be positive" }
        if (authScheme == AuthScheme.CUSTOM_HEADER) {
            require(!customAuthHeader.isNullOrBlank()) {
                "customAuthHeader is required when authScheme is CUSTOM_HEADER"
            }
        }
    }

    /** REST engines are addressed by model ID; MCP engines are addressed by tool name and the model ID is optional. */
    val isConfigured: Boolean
        get() = endpointUrl.isNotBlank() &&
            when (protocol) {
                ProviderProtocol.REST -> modelId.isNotBlank()
                ProviderProtocol.MCP -> !mcpToolName.isNullOrBlank()
            }

    /** Compact engine name for chips and headers, empty when the slot is not configured. */
    val shortLabel: String
        get() = when {
            !isConfigured -> ""
            protocol == ProviderProtocol.MCP -> "MCP · ${mcpToolName.orEmpty()}"
            else -> modelId.substringAfterLast('/')
        }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS: Int = 120

        fun unconfigured(slot: EngineSlot): EngineConfig = EngineConfig(
            slot = slot,
            protocol = ProviderProtocol.REST,
            endpointUrl = "",
            modelId = "",
        )
    }
}
