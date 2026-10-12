package com.visionstudio.core.network.engine

import com.visionstudio.core.common.dispatchers.DefaultDispatcher
import com.visionstudio.core.common.dispatchers.IoDispatcher
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.result.Result
import com.visionstudio.core.common.result.getOrThrow
import com.visionstudio.core.common.result.suspendRunCatching
import com.visionstudio.core.mcp.McpClientFactory
import com.visionstudio.core.mcp.McpServerConfig
import com.visionstudio.core.model.AuthScheme
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.core.model.RestDialect
import com.visionstudio.core.network.engine.mcp.McpGenerationEngine
import com.visionstudio.core.network.engine.rest.CustomRestEngine
import com.visionstudio.core.network.engine.rest.FalQueueEngine
import com.visionstudio.core.network.engine.rest.OpenAiCompatibleEngine
import com.visionstudio.core.network.http.RestHttpClient
import com.visionstudio.core.security.SecurePreferencesRepository
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the adapter for an engine slot at call time, so a protocol or dialect change in
 * Settings applies to the very next request without restarting anything.
 *
 * Engines are cheap, short-lived objects. Callers own them and must [GenerationEngine.close]
 * them when done, which matters for MCP because each engine holds an SSE session.
 */
@Singleton
class EngineFactory @Inject constructor(
    private val http: RestHttpClient,
    private val mcpClientFactory: McpClientFactory,
    private val securePreferences: SecurePreferencesRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {

    /** Builds an engine from an explicit (possibly unsaved) config, e.g. for Settings probes. */
    fun create(config: EngineConfig, apiKey: String?): GenerationEngine {
        validate(config, apiKey)
        return when (config.protocol) {
            ProviderProtocol.REST -> when (config.restDialect) {
                RestDialect.OPENAI_COMPATIBLE -> OpenAiCompatibleEngine(config, apiKey, http, ioDispatcher)
                RestDialect.FAL_QUEUE -> FalQueueEngine(config, apiKey, http, ioDispatcher)
                RestDialect.CUSTOM -> CustomRestEngine(config, apiKey, http, ioDispatcher)
            }
            ProviderProtocol.MCP -> McpGenerationEngine(
                config = config,
                apiKey = apiKey,
                client = mcpClientFactory.create(
                    McpServerConfig(
                        sseUrl = config.endpointUrl,
                        headers = config.authHeaders(apiKey),
                        requestTimeoutMillis = config.requestTimeoutSeconds * MILLIS_PER_SECOND,
                    ),
                ),
                http = http,
                ioDispatcher = ioDispatcher,
                defaultDispatcher = defaultDispatcher,
            )
        }
    }

    /** Builds the engine for [slot] from its persisted config and Keystore-held key. */
    suspend fun forSlot(slot: EngineSlot): Result<GenerationEngine> = suspendRunCatching {
        val config = securePreferences.getEngineConfig(slot).getOrThrow()
        if (!config.isConfigured) {
            throw VisionStudioException.ValidationException(null, "${slot.displayName} engine is not configured")
        }
        val apiKey = securePreferences.getApiKey(slot).getOrThrow()
        create(config, apiKey)
    }

    private fun validate(config: EngineConfig, apiKey: String?) {
        val url = config.endpointUrl.toHttpUrlOrNull()
        if (url == null && !(config.protocol == ProviderProtocol.REST && config.restDialect == RestDialect.FAL_QUEUE && config.endpointUrl.isBlank())) {
            throw VisionStudioException.ValidationException("endpointUrl", "Enter a valid http(s) endpoint URL")
        }
        if (url != null && !url.isHttps && !url.isLocalDevelopmentHost()) {
            throw VisionStudioException.ValidationException("endpointUrl", "Use HTTPS; plain HTTP is only allowed for local hosts")
        }
        if (config.modelId.isBlank() && config.protocol == ProviderProtocol.REST) {
            throw VisionStudioException.ValidationException("modelId", "Model ID is required")
        }
        if (config.protocol == ProviderProtocol.MCP && config.mcpToolName.isNullOrBlank()) {
            throw VisionStudioException.ValidationException("mcpToolName", "Tool name is required for MCP engines")
        }
        if (config.authScheme != AuthScheme.NONE && apiKey.isNullOrBlank()) {
            throw VisionStudioException.ValidationException("apiKey", "API key is required for ${config.authScheme.name.lowercase()} auth")
        }
    }

    /** Must stay in sync with the cleartext allow-list in the app's network_security_config.xml. */
    private fun okhttp3.HttpUrl.isLocalDevelopmentHost(): Boolean = host in CLEARTEXT_HOSTS

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        /** Loopback and the Android emulator's alias for the host machine. */
        val CLEARTEXT_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")
    }
}
