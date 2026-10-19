package com.visionstudio.core.mcp

import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.error.toVisionStudioException
import com.visionstudio.core.mcp.protocol.CallToolParams
import com.visionstudio.core.mcp.protocol.CallToolResult
import com.visionstudio.core.mcp.protocol.CancelledParams
import com.visionstudio.core.mcp.protocol.Implementation
import com.visionstudio.core.mcp.protocol.InitializeParams
import com.visionstudio.core.mcp.protocol.InitializeResult
import com.visionstudio.core.mcp.protocol.JsonRpcError
import com.visionstudio.core.mcp.protocol.JsonRpcNotification
import com.visionstudio.core.mcp.protocol.JsonRpcRequest
import com.visionstudio.core.mcp.protocol.JsonRpcResponse
import com.visionstudio.core.mcp.protocol.ListToolsParams
import com.visionstudio.core.mcp.protocol.ListToolsResult
import com.visionstudio.core.mcp.protocol.MCP_PROTOCOL_VERSION
import com.visionstudio.core.mcp.protocol.McpJson
import com.visionstudio.core.mcp.protocol.McpMethods
import com.visionstudio.core.mcp.protocol.McpNotification
import com.visionstudio.core.mcp.protocol.McpProgress
import com.visionstudio.core.mcp.protocol.McpTool
import com.visionstudio.core.mcp.protocol.ProgressParams
import com.visionstudio.core.mcp.protocol.RequestMeta
import com.visionstudio.core.mcp.transport.SseEvent
import com.visionstudio.core.mcp.transport.SseEventParser
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Model Context Protocol client over the HTTP+SSE transport (protocol revision 2024-11-05).
 *
 * The client opens a long-lived `text/event-stream` GET. The server's first `endpoint` event
 * names the URL for JSON-RPC POSTs, and responses, server requests and notifications all
 * arrive back on the stream as `message` events, correlated here by request id.
 *
 * Dispatchers: socket I/O runs on [ioDispatcher]; JSON-RPC encoding and decoding runs on
 * [defaultDispatcher].
 *
 * Failures are thrown as [VisionStudioException]: protocol and JSON-RPC errors become
 * [VisionStudioException.ProtocolException], HTTP 401/403 becomes AuthenticationException,
 * deadlines become TimeoutException, and transport loss becomes NetworkException.
 */
class McpClient internal constructor(
    private val httpClient: HttpClient,
    private val config: McpServerConfig,
    private val clientInfo: Implementation,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher,
) {
    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data class Ready(val server: InitializeResult) : State
        data class Failed(val error: VisionStudioException) : State
        data object Closed : State
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val connectMutex = Mutex()
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>()
    private val progressHandlers = ConcurrentHashMap<String, (McpProgress) -> Unit>()

    @Volatile private var endpoint = CompletableDeferred<String>()
    @Volatile private var streamJob: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _notifications = MutableSharedFlow<McpNotification>(extraBufferCapacity = NOTIFICATION_BUFFER)
    val notifications: SharedFlow<McpNotification> = _notifications.asSharedFlow()

    /**
     * Opens the stream and performs the `initialize` → `notifications/initialized` handshake.
     * Idempotent while connected; after a failure, calling it again starts a fresh session.
     */
    suspend fun connect(): InitializeResult = connectMutex.withLock {
        when (val current = _state.value) {
            is State.Ready -> return current.server
            State.Closed -> throw IllegalStateException("McpClient is closed")
            else -> Unit
        }
        _state.value = State.Connecting
        try {
            streamJob?.cancelAndJoin()
            endpoint = CompletableDeferred()
            streamJob = scope.launch { runEventStream() }

            withTimeoutOrNull(config.connectTimeoutMillis) { endpoint.await() }
                ?: throw VisionStudioException.TimeoutException("MCP server did not announce an endpoint")

            val result = requestTyped(
                method = McpMethods.INITIALIZE,
                params = InitializeParams(MCP_PROTOCOL_VERSION, JsonObject(emptyMap()), clientInfo),
                paramsSerializer = InitializeParams.serializer(),
                resultSerializer = InitializeResult.serializer(),
            )
            if (result.protocolVersion != MCP_PROTOCOL_VERSION) {
                throw VisionStudioException.ProtocolException(
                    rpcErrorCode = null,
                    message = "Unsupported MCP protocol version ${result.protocolVersion}; client speaks $MCP_PROTOCOL_VERSION",
                )
            }
            notify(McpMethods.INITIALIZED, null)
            _state.value = State.Ready(result)
            result
        } catch (cancellation: CancellationException) {
            teardownStream(VisionStudioException.NetworkException("Connection attempt cancelled"))
            _state.value = State.Idle
            throw cancellation
        } catch (failure: Throwable) {
            val mapped = failure.toVisionStudioException()
            teardownStream(mapped)
            _state.value = State.Failed(mapped)
            throw mapped
        }
    }

    /** Lists every tool, following `nextCursor` pagination. */
    suspend fun listTools(): List<McpTool> {
        requireReady()
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = requestTyped(
                method = McpMethods.TOOLS_LIST,
                params = ListToolsParams(cursor),
                paramsSerializer = ListToolsParams.serializer(),
                resultSerializer = ListToolsResult.serializer(),
            )
            tools += page.tools
            cursor = page.nextCursor?.takeIf { it.isNotEmpty() }
        } while (cursor != null && ++pages < MAX_TOOL_PAGES)
        return tools
    }

    /**
     * Invokes [name]. When [onProgress] is set, a progress token is attached and server
     * `notifications/progress` for this call are forwarded to it.
     *
     * A result with `isError = true` is returned as-is (it is a tool-level failure, not a
     * protocol error), so the caller can show the tool's message.
     */
    suspend fun callTool(
        name: String,
        arguments: JsonObject,
        timeoutMillis: Long = config.requestTimeoutMillis,
        onProgress: ((McpProgress) -> Unit)? = null,
    ): CallToolResult {
        requireReady()
        val token = onProgress?.let { "vs-progress-${nextId.get()}-${System.nanoTime()}" }
        if (token != null) progressHandlers[token] = onProgress
        try {
            return requestTyped(
                method = McpMethods.TOOLS_CALL,
                params = CallToolParams(name, arguments, token?.let(::RequestMeta)),
                paramsSerializer = CallToolParams.serializer(),
                resultSerializer = CallToolResult.serializer(),
                timeoutMillis = timeoutMillis,
            )
        } finally {
            if (token != null) progressHandlers.remove(token)
        }
    }

    suspend fun ping() {
        requireReady()
        request(McpMethods.PING, null)
    }

    /** Fails in-flight requests, closes the stream and releases the scope. Terminal. */
    suspend fun close() {
        // Releasing resources must finish even if the caller is itself being cancelled (a screen
        // closing, a worker being stopped); otherwise the stream and scope would leak.
        withContext(NonCancellable) {
            connectMutex.withLock {
                if (_state.value == State.Closed) return@withContext
                teardownStream(VisionStudioException.NetworkException("MCP client closed"))
                _state.value = State.Closed
            }
            scope.cancel()
        }
    }

    // region transport

    private suspend fun runEventStream() {
        try {
            httpClient.prepareGet(config.sseUrl) {
                header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
                header(HttpHeaders.CacheControl, "no-cache")
                applyConfiguredHeaders()
                timeout {
                    requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                }
            }.execute { response ->
                response.throwIfUnsuccessful()
                if (response.contentType()?.match(ContentType.Text.EventStream) != true) {
                    throw VisionStudioException.ProtocolException(
                        null,
                        "Expected text/event-stream but server sent ${response.contentType()}",
                    )
                }
                val channel = response.bodyAsChannel()
                val parser = SseEventParser()
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    parser.feedLine(line)?.let { handleEvent(it) }
                }
            }
            failInFlight(VisionStudioException.NetworkException("MCP event stream closed by server"))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            failInFlight(failure.toVisionStudioException())
        }
    }

    private suspend fun handleEvent(event: SseEvent) {
        when (event.event) {
            EVENT_ENDPOINT -> endpoint.complete(resolveEndpoint(event.data.trim()))
            SseEventParser.DEFAULT_EVENT -> {
                val element = withContext(defaultDispatcher) {
                    runCatching { McpJson.parseToJsonElement(event.data) }.getOrNull()
                } ?: return
                when (element) {
                    is JsonArray -> element.forEach { (it as? JsonObject)?.let { obj -> handleMessage(obj) } }
                    is JsonObject -> handleMessage(element)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun handleMessage(message: JsonObject) {
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        val id = message["id"]?.takeUnless { it is JsonNull }
        when {
            method == null && id != null -> {
                val response = withContext(defaultDispatcher) {
                    runCatching { McpJson.decodeFromJsonElement(JsonRpcResponse.serializer(), message) }.getOrNull()
                } ?: return
                id.asRequestId()?.let { pending.remove(it)?.complete(response) }
            }
            method != null && id != null -> answerServerRequest(id, method)
            method != null -> handleNotification(method, message["params"])
        }
    }

    private fun handleNotification(method: String, params: JsonElement?) {
        if (method == McpMethods.PROGRESS && params != null) dispatchProgress(params)
        _notifications.tryEmit(McpNotification(method, params))
    }

    /**
     * Progress is advisory. A malformed token, or a bug in the caller's callback, must never take
     * down the shared event stream, because that would fail every other request in flight.
     */
    private fun dispatchProgress(params: JsonElement) {
        val progress = runCatching { McpJson.decodeFromJsonElement(ProgressParams.serializer(), params) }.getOrNull() ?: return
        val token = (progress.progressToken as? JsonPrimitive)?.contentOrNull ?: return
        val handler = progressHandlers[token] ?: return
        try {
            handler(McpProgress(progress.progress, progress.total, progress.message))
        } catch (callbackFailure: Exception) {
            // Intentionally contained: the callback belongs to the caller, not to the transport.
        }
    }

    /** The client exposes no capabilities, so it answers only `ping`. */
    private suspend fun answerServerRequest(id: JsonElement, method: String) {
        val reply = if (method == McpMethods.PING) {
            JsonRpcResponse(id = id, result = JsonObject(emptyMap()))
        } else {
            JsonRpcResponse(id = id, error = JsonRpcError(JsonRpcError.METHOD_NOT_FOUND, "Method not supported: $method"))
        }
        val body = withContext(defaultDispatcher) { McpJson.encodeToString(JsonRpcResponse.serializer(), reply) }
        runCatching { postMessage(body) }
    }

    private suspend fun <P, R> requestTyped(
        method: String,
        params: P,
        paramsSerializer: KSerializer<P>,
        resultSerializer: KSerializer<R>,
        timeoutMillis: Long = config.requestTimeoutMillis,
    ): R {
        val encodedParams = withContext(defaultDispatcher) { McpJson.encodeToJsonElement(paramsSerializer, params) }
        val result = request(method, encodedParams, timeoutMillis)
        return withContext(defaultDispatcher) {
            try {
                McpJson.decodeFromJsonElement(resultSerializer, result)
            } catch (malformed: IllegalArgumentException) {
                throw VisionStudioException.ProtocolException(
                    JsonRpcError.PARSE_ERROR,
                    "Malformed $method result: ${malformed.message}",
                    malformed,
                )
            }
        }
    }

    private suspend fun request(
        method: String,
        params: JsonElement?,
        timeoutMillis: Long = config.requestTimeoutMillis,
    ): JsonElement {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pending[id] = deferred
        try {
            val body = withContext(defaultDispatcher) {
                McpJson.encodeToString(JsonRpcRequest.serializer(), JsonRpcRequest(id = id, method = method, params = params))
            }
            postMessage(body)
            val response = awaitResponse(id, method, deferred, timeoutMillis)
            response.error?.let { error ->
                throw VisionStudioException.ProtocolException(error.code, "MCP $method failed: ${error.message}")
            }
            return response.result ?: JsonObject(emptyMap())
        } finally {
            pending.remove(id)
        }
    }

    /** Waits for the correlated response; on timeout or cancellation tells the server to stop. */
    private suspend fun awaitResponse(
        id: Long,
        method: String,
        deferred: CompletableDeferred<JsonRpcResponse>,
        timeoutMillis: Long,
    ): JsonRpcResponse {
        val response = try {
            withTimeoutOrNull(timeoutMillis) { deferred.await() }
        } catch (cancellation: CancellationException) {
            sendCancelled(id, "Client cancelled $method")
            throw cancellation
        }
        if (response == null) {
            sendCancelled(id, "Client timed out after ${timeoutMillis}ms")
            throw VisionStudioException.TimeoutException("MCP $method timed out after ${timeoutMillis}ms")
        }
        return response
    }

    private suspend fun notify(method: String, params: JsonElement?) {
        val body = withContext(defaultDispatcher) {
            McpJson.encodeToString(JsonRpcNotification.serializer(), JsonRpcNotification(method = method, params = params))
        }
        postMessage(body)
    }

    private fun sendCancelled(id: Long, reason: String) {
        scope.launch(NonCancellable) {
            runCatching {
                notify(
                    McpMethods.CANCELLED,
                    McpJson.encodeToJsonElement(CancelledParams.serializer(), CancelledParams(id, reason)),
                )
            }
        }
    }

    private suspend fun postMessage(body: String) {
        if (!endpoint.isCompleted) throw VisionStudioException.NetworkException("MCP endpoint not yet announced")
        val target = endpoint.await()
        val response = withContext(ioDispatcher) {
            httpClient.post(target) {
                contentType(ContentType.Application.Json)
                applyConfiguredHeaders()
                setBody(body)
            }
        }
        response.throwIfUnsuccessful()
        // Lenient path: some servers return the JSON-RPC response inline instead of over SSE.
        if (response.status == HttpStatusCode.OK &&
            response.contentType()?.match(ContentType.Application.Json) == true
        ) {
            val inline = response.bodyAsText()
            if (inline.isNotBlank()) handleEvent(SseEvent(SseEventParser.DEFAULT_EVENT, inline, null, null))
        }
    }

    // endregion

    // region helpers

    private fun HttpRequestBuilder.applyConfiguredHeaders() {
        config.headers.forEach { (name, value) -> header(name, value) }
    }

    private suspend fun HttpResponse.throwIfUnsuccessful() {
        if (status.isSuccess()) return
        val detail = runCatching { bodyAsText().take(MAX_ERROR_BODY) }.getOrNull()
        throw when (status) {
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                VisionStudioException.AuthenticationException(null, status.value, "MCP server rejected credentials (${status.value})")
            else -> VisionStudioException.ProviderException(status.value, detail?.takeIf { it.isNotBlank() })
        }
    }

    /** Resolves the announced endpoint and enforces same-origin, as the spec requires. */
    private fun resolveEndpoint(raw: String): String {
        val base = URI(config.sseUrl)
        val resolved = base.resolve(raw)
        val sameOrigin = resolved.scheme.equals(base.scheme, ignoreCase = true) &&
            resolved.host.equals(base.host, ignoreCase = true) &&
            resolved.effectivePort() == base.effectivePort()
        if (!sameOrigin) {
            val error = VisionStudioException.ProtocolException(null, "MCP endpoint $resolved is not same-origin with ${config.sseUrl}")
            endpoint.completeExceptionally(error)
            throw error
        }
        return resolved.toString()
    }

    private fun URI.effectivePort(): Int = when {
        port != -1 -> port
        scheme.equals("https", ignoreCase = true) -> HTTPS_PORT
        else -> HTTP_PORT
    }

    private fun JsonElement.asRequestId(): Long? =
        (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

    private fun requireReady() {
        if (_state.value !is State.Ready) {
            throw VisionStudioException.ValidationException(null, "MCP client is not connected; call connect() first")
        }
    }

    private fun failInFlight(error: VisionStudioException) {
        // Publish the failed state before waking callers, so anyone handling the error
        // already observes State.Failed (not a stale Ready) and can reconnect.
        if (_state.value is State.Ready) _state.value = State.Failed(error)
        endpoint.completeExceptionally(error)
        val inFlight = pending.values.toList()
        pending.clear()
        inFlight.forEach { it.completeExceptionally(error) }
    }

    private suspend fun teardownStream(error: VisionStudioException) {
        failInFlight(error)
        streamJob?.cancelAndJoin()
        streamJob = null
    }

    // endregion

    private companion object {
        const val EVENT_ENDPOINT = "endpoint"
        const val MAX_TOOL_PAGES = 50
        const val MAX_ERROR_BODY = 512
        const val NOTIFICATION_BUFFER = 64
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443
    }
}
