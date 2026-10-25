package com.visionstudio.core.mcp

import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.mcp.protocol.JsonRpcError
import com.visionstudio.core.mcp.protocol.MCP_PROTOCOL_VERSION
import com.visionstudio.core.mcp.protocol.McpProgress
import com.visionstudio.core.mcp.protocol.McpTool
import com.visionstudio.core.mcp.protocol.ToolContent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

/**
 * Exercises [McpClient] against [FakeMcpServer]. Uses real dispatchers and wall-clock time:
 * MockEngine runs on its own threads, so virtual time would fire request timeouts early.
 */
class McpClientTest {

    private val clients = mutableListOf<McpClient>()

    private fun mcpTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) { block() }
    }

    private fun FakeMcpServer.client(
        requestTimeoutMillis: Long = 5_000,
        connectTimeoutMillis: Long = 2_000,
        headers: Map<String, String> = mapOf("Authorization" to "Bearer test-token"),
        maxEventBytes: Int = McpServerConfig.DEFAULT_MAX_EVENT_BYTES,
    ): McpClient = McpClientFactory(
        httpClient = createMcpHttpClient(engine),
        ioDispatcher = Dispatchers.IO,
        defaultDispatcher = Dispatchers.Default,
        clientInfo = McpClientFactory.DEFAULT_CLIENT_INFO,
    ).create(
        McpServerConfig(
            url = "https://mcp.example.com/sse",
            // FakeMcpServer is a legacy 2024-11-05 server; Streamable HTTP is covered elsewhere.
            transport = McpTransport.SSE,
            headers = headers,
            connectTimeoutMillis = connectTimeoutMillis,
            requestTimeoutMillis = requestTimeoutMillis,
            maxEventBytes = maxEventBytes,
        ),
    ).also { clients += it }

    @After
    fun tearDown() = runBlocking { clients.forEach { it.close() } }

    // region handshake

    @Test
    fun `connect performs initialize handshake with protocol 2024-11-05`() = mcpTest {
        val server = FakeMcpServer()
        val client = server.client()

        val result = client.connect()

        assertEquals("fake-diffusion", result.serverInfo.name)
        assertTrue(client.state.value is McpClient.State.Ready)

        val init = server.requestsFor("initialize").single()
        assertEquals("2.0", init["jsonrpc"]!!.jsonPrimitive.content)
        val params = init["params"]!!.jsonObject
        assertEquals(MCP_PROTOCOL_VERSION, params["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("VisionStudio AI", params["clientInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        eventually { server.requestsFor("notifications/initialized").size == 1 }
        val initialized = server.requestsFor("notifications/initialized").single()
        assertFalse("notifications must not carry an id", initialized.containsKey("id"))
    }

    @Test
    fun `messages are posted to the announced endpoint with auth headers on every request`() = mcpTest {
        val server = FakeMcpServer()
        server.client().connect()

        assertTrue(server.receivedHeaders.all { it["Authorization"] == "Bearer test-token" })
        assertTrue(server.receivedHeaders.first()["Accept"]!!.contains("text/event-stream"))
    }

    @Test
    fun `connect is idempotent once ready`() = mcpTest {
        val server = FakeMcpServer()
        val client = server.client()
        client.connect()
        client.connect()
        assertEquals(1, server.requestsFor("initialize").size)
    }

    @Test
    fun `protocol version mismatch fails the handshake`() = mcpTest {
        val server = FakeMcpServer()
        server.onRequest = { message ->
            val id = message["id"]
            if (id == null) emptyList()
            else listOf(FakeMcpServer.result(id, FakeMcpServer.INITIALIZE_RESULT.replace("2024-11-05", "2099-01-01")))
        }
        val client = server.client()

        val error = expectFailure<VisionStudioException.ProtocolException> { client.connect() }

        assertTrue(error.message!!.contains("2099-01-01"))
        assertTrue(client.state.value is McpClient.State.Failed)
    }

    @Test
    fun `missing endpoint event is reported as a transport mismatch, keeping the timeout as cause`() = mcpTest {
        val server = FakeMcpServer(announceEndpoint = false)
        val error = expectFailure<VisionStudioException.UnsupportedTransportException> { server.client(connectTimeoutMillis = 300).connect() }
        assertTrue(error.cause is VisionStudioException.TimeoutException)
        assertTrue(error.message!!.contains("Streamable HTTP"))
    }

    @Test
    fun `cross-origin endpoint is rejected`() = mcpTest {
        val server = FakeMcpServer(endpointPath = "https://evil.example.net/steal")
        expectFailure<VisionStudioException.ProtocolException> { server.client().connect() }
    }

    @Test
    fun `401 on the stream maps to AuthenticationException`() = mcpTest {
        val server = FakeMcpServer(sseStatus = HttpStatusCode.Unauthorized)
        val error = expectFailure<VisionStudioException.AuthenticationException> { server.client().connect() }
        assertEquals(401, error.httpCode)
    }

    @Test
    fun `non event-stream content type is reported as a transport mismatch`() = mcpTest {
        val server = FakeMcpServer(sseContentType = ContentType.Text.Html)
        val error = expectFailure<VisionStudioException.UnsupportedTransportException> { server.client().connect() }
        assertTrue(error.cause is VisionStudioException.ProtocolException)
    }

    @Test
    fun `json-rpc error response during initialize surfaces code`() = mcpTest {
        val server = FakeMcpServer()
        server.onRequest = { message ->
            message["id"]?.let { listOf(FakeMcpServer.error(it, JsonRpcError.INVALID_PARAMS, "bad clientInfo")) }
                ?: emptyList()
        }
        val error = expectFailure<VisionStudioException.ProtocolException> { server.client().connect() }
        assertEquals(JsonRpcError.INVALID_PARAMS, error.rpcErrorCode)
    }

    // endregion

    // region tools

    @Test
    fun `tools list decodes schema and follows pagination`() = mcpTest {
        val server = FakeMcpServer()
        server.onRequest = { message ->
            val id = message["id"]
            val cursor = message["params"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content
            when {
                id == null -> emptyList()
                message["method"]!!.jsonPrimitive.content != "tools/list" -> defaultHandler(message)
                cursor == null -> listOf(
                    FakeMcpServer.result(id, """{"tools":[{"name":"generate_image","inputSchema":{"type":"object"}}],"nextCursor":"p2"}"""),
                )
                else -> listOf(FakeMcpServer.result(id, """{"tools":[{"name":"upscale","description":"4x"}]}"""))
            }
        }
        val client = server.client()
        client.connect()

        val tools = client.listTools()

        assertEquals(listOf("generate_image", "upscale"), tools.map { it.name })
        assertEquals(JsonPrimitive("object"), tools.first().inputSchema["type"])
        assertEquals("4x", tools.last().description)
    }

    @Test
    fun `tools call returns text and image content`() = mcpTest {
        val server = FakeMcpServer()
        server.onRequest = { message ->
            val id = message["id"]
            when {
                id == null -> emptyList()
                message["method"]!!.jsonPrimitive.content == "tools/call" -> listOf(
                    FakeMcpServer.result(
                        id,
                        """{"content":[{"type":"text","text":"done"},{"type":"image","data":"iVBORw0KGgo=","mimeType":"image/png"},{"type":"audio","data":"x"}],"isError":false}""",
                    ),
                )
                else -> defaultHandler(message)
            }
        }
        val client = server.client()
        client.connect()

        val result = client.callTool("generate_image", buildJsonObject { put("prompt", "neon koi") })

        assertEquals("done", result.text)
        val image = result.content.filterIsInstance<ToolContent.Image>().single()
        assertEquals("image/png", image.mimeType)
        assertTrue(result.content.last() is ToolContent.Unknown)

        val call = server.requestsFor("tools/call").single()["params"]!!.jsonObject
        assertEquals("generate_image", call["name"]!!.jsonPrimitive.content)
        assertEquals("neon koi", call["arguments"]!!.jsonObject["prompt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool level isError is returned not thrown`() = mcpTest {
        val server = FakeMcpServer()
        server.onRequest = { message ->
            val id = message["id"]
            when {
                id == null -> emptyList()
                message["method"]!!.jsonPrimitive.content == "tools/call" ->
                    listOf(FakeMcpServer.result(id, """{"content":[{"type":"text","text":"NSFW prompt rejected"}],"isError":true}"""))
                else -> defaultHandler(message)
            }
        }
        val client = server.client()
        client.connect()

        val result = client.callTool("generate_image", JsonObject(emptyMap()))

        assertTrue(result.isError)
        assertEquals("NSFW prompt rejected", result.text)
    }

    @Test
    fun `unknown tool json-rpc error maps to ProtocolException`() = mcpTest {
        val server = FakeMcpServer()
        val client = server.client()
        client.connect()

        val error = expectFailure<VisionStudioException.ProtocolException> {
            client.callTool("does_not_exist", JsonObject(emptyMap()))
        }
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, error.rpcErrorCode)
    }

    @Test
    fun `progress notifications are routed to the matching call`() = mcpTest {
        val server = FakeMcpServer()
        server.onRequest = { message ->
            val id = message["id"]
            when {
                id == null -> emptyList()
                message["method"]!!.jsonPrimitive.content == "tools/call" -> {
                    val token = message["params"]!!.jsonObject["_meta"]!!.jsonObject["progressToken"]!!
                    listOf(
                        FakeMcpServer.sseFrame("message", """{"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":$token,"progress":5,"total":20}}"""),
                        FakeMcpServer.sseFrame("message", """{"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":$token,"progress":20,"total":20,"message":"decoding"}}"""),
                        FakeMcpServer.result(id, """{"content":[{"type":"text","text":"ok"}]}"""),
                    )
                }
                else -> defaultHandler(message)
            }
        }
        val client = server.client()
        client.connect()
        val updates = Collections.synchronizedList(mutableListOf<McpProgress>())

        client.callTool("generate_image", JsonObject(emptyMap()), onProgress = { updates += it })

        assertEquals(listOf(0.25f, 1f), updates.map { it.fraction })
        assertEquals("decoding", updates.last().message)
    }

    @Test
    fun `out of order responses are correlated by id`() = mcpTest {
        val server = FakeMcpServer()
        val heldIds = Collections.synchronizedList(mutableListOf<kotlinx.serialization.json.JsonElement>())
        server.onRequest = { message ->
            val id = message["id"]
            when {
                id == null -> emptyList()
                message["method"]!!.jsonPrimitive.content == "tools/call" -> {
                    heldIds += id
                    if (heldIds.size < 2) {
                        emptyList()
                    } else {
                        // Answer the second call first, then the first.
                        heldIds.reversed().map { held ->
                            FakeMcpServer.result(held, """{"content":[{"type":"text","text":"id-${held.jsonPrimitive.long}"}]}""")
                        }
                    }
                }
                else -> defaultHandler(message)
            }
        }
        val client = server.client()
        client.connect()

        val first = async { client.callTool("a", JsonObject(emptyMap())) }
        eventually { heldIds.size == 1 }
        val second = async { client.callTool("b", JsonObject(emptyMap())) }

        val firstId = heldIds[0].jsonPrimitive.long
        assertEquals("id-$firstId", first.await().text)
        assertEquals("id-${firstId + 1}", second.await().text)
    }

    // endregion

    // region failure handling

    @Test
    fun `request timeout throws TimeoutException and notifies server of cancellation`() = mcpTest {
        val server = FakeMcpServer()
        server.onRequest = { message ->
            if (message["method"]!!.jsonPrimitive.content == "tools/call") emptyList() else defaultHandler(message)
        }
        val client = server.client()
        client.connect()

        expectFailure<VisionStudioException.TimeoutException> {
            client.callTool("generate_image", JsonObject(emptyMap()), timeoutMillis = 250)
        }

        eventually { server.requestsFor("notifications/cancelled").isNotEmpty() }
        val callId = server.requestsFor("tools/call").single()["id"]!!.jsonPrimitive.long
        val cancelled = server.requestsFor("notifications/cancelled").single()["params"]!!.jsonObject
        assertEquals(callId, cancelled["requestId"]!!.jsonPrimitive.long)
    }

    @Test
    fun `stream drop fails in-flight requests with NetworkException`() = mcpTest {
        val server = FakeMcpServer()
        server.onRequest = { message ->
            if (message["method"]!!.jsonPrimitive.content == "tools/call") emptyList() else defaultHandler(message)
        }
        val client = server.client()
        client.connect()

        val call = async { runCatching { client.callTool("generate_image", JsonObject(emptyMap())) } }
        eventually { server.requestsFor("tools/call").isNotEmpty() }
        server.closeStream()

        val failure = call.await().exceptionOrNull()
        assertTrue("expected NetworkException, got $failure", failure is VisionStudioException.NetworkException)
        assertTrue(client.state.value is McpClient.State.Failed)
    }

    @Test
    fun `malformed frames are skipped without killing the session`() = mcpTest {
        val server = FakeMcpServer()
        server.onRequest = { message ->
            val id = message["id"]
            if (id != null && message["method"]!!.jsonPrimitive.content == "ping") {
                listOf(FakeMcpServer.sseFrame("message", "{not json"), FakeMcpServer.result(id, "{}"))
            } else {
                defaultHandler(message)
            }
        }
        val client = server.client()
        client.connect()

        client.ping()

        assertTrue(client.state.value is McpClient.State.Ready)
    }

    @Test
    fun `server initiated ping is answered`() = mcpTest {
        val server = FakeMcpServer()
        val client = server.client()
        client.connect()

        server.push(FakeMcpServer.sseFrame("message", """{"jsonrpc":"2.0","id":"srv-1","method":"ping"}"""))

        eventually { server.received.any { it["id"]?.jsonPrimitive?.content == "srv-1" && it.containsKey("result") } }
    }

    @Test
    fun `close completes even when called from a cancelled coroutine`() = mcpTest {
        val server = FakeMcpServer()
        val client = server.client()
        client.connect()

        // A caller being torn down (screen closed, worker stopped) still has to release the session.
        val closer = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            runCatching { client.close() }
        }
        closer.join()

        assertEquals("the client reached its terminal state despite the cancelled caller", McpClient.State.Closed, client.state.value)
    }

    @Test
    fun `a malformed progress notification does not kill the session`() = mcpTest {
        val server = FakeMcpServer()
        server.onRequest = { message ->
            val id = message["id"]
            when {
                id == null -> emptyList()
                message["method"]!!.jsonPrimitive.content == "tools/call" -> listOf(
                    // progressToken is an object, which has no string form
                    FakeMcpServer.sseFrame("message", """{"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":{"a":1},"progress":1}}"""),
                    FakeMcpServer.result(id, """{"content":[{"type":"text","text":"still alive"}]}"""),
                )
                else -> defaultHandler(message)
            }
        }
        val client = server.client()
        client.connect()

        val result = client.callTool("generate_image", JsonObject(emptyMap()), onProgress = { })

        assertEquals("still alive", result.text)
        assertTrue(client.state.value is McpClient.State.Ready)
    }

    @Test
    fun `a progress callback that throws does not kill the session either`() = mcpTest {
        val server = FakeMcpServer()
        server.onRequest = { message ->
            val id = message["id"]
            when {
                id == null -> emptyList()
                message["method"]!!.jsonPrimitive.content == "tools/call" -> {
                    val token = message["params"]!!.jsonObject["_meta"]!!.jsonObject["progressToken"]!!
                    listOf(
                        FakeMcpServer.sseFrame("message", """{"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":$token,"progress":1,"total":2}}"""),
                        FakeMcpServer.result(id, """{"content":[{"type":"text","text":"done"}]}"""),
                    )
                }
                else -> defaultHandler(message)
            }
        }
        val client = server.client()
        client.connect()

        val result = client.callTool("generate_image", JsonObject(emptyMap()), onProgress = { error("UI callback bug") })

        assertEquals("done", result.text)
        assertTrue(client.state.value is McpClient.State.Ready)
    }

    @Test
    fun `a stalled post counts against the request deadline instead of hanging`() = mcpTest {
        val server = FakeMcpServer()
        val stall = CompletableDeferred<Unit>()
        server.beforePostResponse = { message -> if (message["method"]?.jsonPrimitive?.content == "tools/call") stall.await() }
        val client = server.client()
        client.connect()

        val started = System.nanoTime()
        expectFailure<VisionStudioException.TimeoutException> {
            client.callTool("generate_image", JsonObject(emptyMap()), timeoutMillis = 300)
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue("timed out after ${elapsedMillis}ms, expected about 300ms", elapsedMillis < 2_000)
        stall.complete(Unit)
    }

    @Test
    fun `a slow reply to a server ping does not hold up responses behind it`() = mcpTest {
        val server = FakeMcpServer()
        val stall = CompletableDeferred<Unit>()
        // The client's reply to the server's ping is a POST that the server holds open.
        server.beforePostResponse = { message -> if (message["id"]?.jsonPrimitive?.content == "srv-slow") stall.await() }
        server.onRequest = { message ->
            if (message["method"]?.jsonPrimitive?.content == "tools/list") {
                listOf(
                    FakeMcpServer.sseFrame("message", """{"jsonrpc":"2.0","id":"srv-slow","method":"ping"}"""),
                    FakeMcpServer.result(message["id"]!!, """{"tools":[]}"""),
                )
            } else {
                defaultHandler(message)
            }
        }
        val client = server.client()
        client.connect()

        val tools = client.listTools()

        assertEquals("the response queued behind the ping arrived while its reply was still stalled", emptyList<McpTool>(), tools)
        assertFalse(stall.isCompleted)
        stall.complete(Unit)
    }

    @Test
    fun `an endless line fails the session with a protocol error instead of exhausting memory`() = mcpTest {
        val server = FakeMcpServer()
        val client = server.client(maxEventBytes = 1_024)
        client.connect()
        server.onRequest = { emptyList() }

        val call = async { runCatching { client.callTool("generate_image", JsonObject(emptyMap())) } }
        eventually { server.requestsFor("tools/call").isNotEmpty() }
        // No newline ever arrives. The line is larger than the channel's buffer, so the write can
        // block until the client reads, and once the client hits its limit it cancels the stream
        // and the pending write fails. It runs on its own coroutine so neither outcome can hold up
        // or fail the test; only the client's verdict is asserted.
        val writer = launch(Dispatchers.IO) { runCatching { server.push("data: " + "A".repeat(8 * 1024)) } }

        val failure = call.await().exceptionOrNull()
        assertTrue("expected ProtocolException, got $failure", failure is VisionStudioException.ProtocolException)
        assertTrue(client.state.value is McpClient.State.Failed)
        writer.join()
    }

    @Test
    fun `an event that never ends fails the session too`() = mcpTest {
        val server = FakeMcpServer()
        val client = server.client(maxEventBytes = 1_024)
        client.connect()
        server.onRequest = { emptyList() }

        val call = async { runCatching { client.callTool("generate_image", JsonObject(emptyMap())) } }
        eventually { server.requestsFor("tools/call").isNotEmpty() }
        // Short lines, but the blank line that would end the event never comes.
        // Once the client gives up it cancels the stream, so later writes may fail; that is expected.
        runCatching { repeat(40) { server.push("data: " + "B".repeat(60) + "\n") } }

        val failure = call.await().exceptionOrNull()
        assertTrue("expected ProtocolException, got $failure", failure is VisionStudioException.ProtocolException)
    }

    @Test
    fun `an event right at the size limit is still delivered`() = mcpTest {
        val server = FakeMcpServer()
        val client = server.client(maxEventBytes = 4_096)
        client.connect()
        val prompt = "\u30F3".repeat(1_000) // multi-byte characters, well inside the limit
        server.onRequest = { message ->
            if (message["method"]?.jsonPrimitive?.content == "tools/call") {
                listOf(FakeMcpServer.result(message["id"]!!, """{"content":[{"type":"text","text":"$prompt"}],"isError":false}"""))
            } else {
                defaultHandler(message)
            }
        }

        val result = client.callTool("generate_image", JsonObject(emptyMap()))

        assertEquals(prompt, (result.content.single() as ToolContent.Text).text)
    }

    @Test
    fun `calls before connect are rejected`() = mcpTest {
        val client = FakeMcpServer().client()
        expectFailure<VisionStudioException.ValidationException> { client.listTools() }
    }

    @Test
    fun `server config toString redacts header values`() {
        val config = McpServerConfig("https://x.dev/sse", headers = mapOf("Authorization" to "Bearer secret"))
        assertFalse(config.toString().contains("secret"))
    }

    // endregion

    private suspend inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (expected: Throwable) {
            if (expected is T) return expected
            fail("Expected ${T::class.simpleName} but got $expected")
        }
        fail("Expected ${T::class.simpleName} but call succeeded")
        throw AssertionError()
    }

    private suspend fun eventually(condition: () -> Boolean) {
        repeat(EVENTUALLY_ATTEMPTS) {
            if (condition()) return
            delay(EVENTUALLY_STEP_MILLIS)
        }
        fail("Condition not met within ${EVENTUALLY_ATTEMPTS * EVENTUALLY_STEP_MILLIS}ms")
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 10_000L
        const val EVENTUALLY_ATTEMPTS = 100
        const val EVENTUALLY_STEP_MILLIS = 20L
    }
}
