package com.visionstudio.core.mcp

import com.visionstudio.core.common.dispatchers.DefaultDispatcher
import com.visionstudio.core.common.dispatchers.IoDispatcher
import com.visionstudio.core.mcp.protocol.Implementation
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds [McpClient]s that share one HTTP engine and its connection pool. Each client
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
        okHttpClient: OkHttpClient,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
    ) : this(createMcpHttpClient(OkHttp.create { preconfigured = okHttpClient }), ioDispatcher, defaultDispatcher, DEFAULT_CLIENT_INFO)

    fun create(config: McpServerConfig): McpClient =
        McpClient(httpClient, config, clientInfo, ioDispatcher, defaultDispatcher)

    companion object {
        val DEFAULT_CLIENT_INFO: Implementation = Implementation(name = "VisionStudio AI", version = "0.1.0")
    }
}

/**
 * Ktor client for MCP over [engine].
 *
 * In the app the engine is Ktor's OkHttp engine wrapping the app's shared OkHttpClient, so TLS
 * is the platform's: the handshake goes through Android's SSLSocket, which validates the chain
 * with the hostname (`X509ExtendedTrustManager` / `X509TrustManagerExtensions`), honours the
 * Network Security Configuration and its cleartext rules, and OkHttp verifies the hostname. Ktor's
 * CIO engine must not be used here: its own TLS calls the hostname-less
 * `X509TrustManager.checkServerTrusted(chain, authType)`, which Android's `RootTrustManager`
 * rejects whenever the app declares any `<domain-config>` ("Domain specific configurations require
 * that hostname aware checkServerTrusted(X509Certificate[], String, String) is used").
 *
 * No engine-level request timeout: an SSE stream lives indefinitely. Deadlines are applied per
 * request through [HttpTimeout] instead.
 */
internal fun createMcpHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = McpServerConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS
        requestTimeoutMillis = McpServerConfig.DEFAULT_REQUEST_TIMEOUT_MILLIS
        socketTimeoutMillis = McpServerConfig.DEFAULT_REQUEST_TIMEOUT_MILLIS
    }
}
