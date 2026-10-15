package com.visionstudio.core.mcp

import com.visionstudio.core.common.dispatchers.DefaultDispatcher
import com.visionstudio.core.common.dispatchers.IoDispatcher
import com.visionstudio.core.mcp.protocol.Implementation
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds [McpClient]s that share one Ktor CIO engine and its connection pool. Each client
 * represents one MCP session; create a new one per engine slot or per reconnect.
 */
@Singleton
class McpClientFactory internal constructor(
    private val httpClient: HttpClient,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher,
    private val clientInfo: Implementation,
) {
    @Inject
    constructor(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
    ) : this(createMcpHttpClient(), ioDispatcher, defaultDispatcher, DEFAULT_CLIENT_INFO)

    fun create(config: McpServerConfig): McpClient =
        McpClient(httpClient, config, clientInfo, ioDispatcher, defaultDispatcher)

    companion object {
        val DEFAULT_CLIENT_INFO: Implementation = Implementation(name = "VisionStudio AI", version = "0.1.0")
    }
}

/**
 * Ktor client tuned for long-lived SSE streams. The CIO engine-level request timeout is
 * disabled (0), because it would otherwise kill the event stream after 15 s. Deadlines are
 * applied per request through [HttpTimeout] instead.
 */
internal fun createMcpHttpClient(engine: HttpClientEngine? = null): HttpClient {
    val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = McpServerConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS
            requestTimeoutMillis = McpServerConfig.DEFAULT_REQUEST_TIMEOUT_MILLIS
            socketTimeoutMillis = McpServerConfig.DEFAULT_REQUEST_TIMEOUT_MILLIS
        }
    }
    return if (engine != null) {
        HttpClient(engine, configure)
    } else {
        HttpClient(CIO) {
            engine {
                requestTimeout = 0
                endpoint {
                    keepAliveTime = KEEP_ALIVE_MILLIS
                    connectAttempts = CONNECT_ATTEMPTS
                }
            }
            configure()
        }
    }
}

private const val KEEP_ALIVE_MILLIS = 30_000L
private const val CONNECT_ATTEMPTS = 2
