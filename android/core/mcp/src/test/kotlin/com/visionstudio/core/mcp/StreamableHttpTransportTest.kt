package com.visionstudio.core.mcp

import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.mcp.protocol.McpProgress
import com.visionstudio.core.mcp.protocol.ToolContent
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** Streamable HTTP over a real socket and the real Ktor OkHttp engine the app uses. */
class StreamableHttpTransportTest {

    private lateinit var server: StreamableMcpServer
    private val clients = mutableListOf<McpClient>()

    @Before fun setUp() {
        server = StreamableMcpServer().start()
    }

    @After fun tearDown() = runBlocking {
        clients.forEach { it.close() }
        server.shutdown()
    }

    private fun client(
        url: String = server.url(),
        transport: McpTransport = McpTransport.AUTO,
        headers: Map<String, String> = emptyMap(),
        requestTimeoutMillis: Long = 5_000,
        okHttp: OkHttpClient = OkHttpClient(),
    ): McpClient = McpClientFactory(
        httpClient = createMcpHttpClient(OkHttp.create { preconfigured = okHttp }),
        ioDispatcher = Dispatchers.IO,
        defaultDispatcher = Dispatchers.Default,
        clientInfo = McpClientFactory.DEFAULT_CLIENT_INFO,
    ).create(
        McpServerConfig(url = url, headers = headers, transport = transport, connectTimeoutMillis = 2_000, requestTimeoutMillis = requestTimeoutMillis),
    ).also { clients += it }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private suspend inline fun <reified T : Throwable> expectFailure(crossinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (expected: Throwable) {
            if (expected is T) return expected
            throw AssertionError("expected ${T::class.simpleName}, got $expected", expected)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    @Test
    fun `initialize, notify and list tools as streamed SSE responses, like a Gradio Space`() = test {
        val client = client()

        val server = client.connect()
        val tools = client.listTools()

        assertEquals("FLUX 3 Image Klein", server.serverInfo.name)
        assertEquals("2025-06-18", server.protocolVersion)
        assertEquals(McpTransport.STREAMABLE_HTTP, client.activeTransport)
        assertTrue(tools.any { it.name == "flux_3_image_klein_infer" })
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), this.server.requests.map { it.method })
        this.server.requests.forEach {
            assertEquals("POST", it.request.method)
            assertEquals("/gradio_api/mcp/", it.request.path)
            assertEquals("application/json, text/event-stream", it.header("Accept"))
            assertTrue(it.header("Content-Type")!!.startsWith("application/json"))
        }
    }

    @Test
    fun `plain JSON responses work too`() = test {
        server.respondWithJson = true
        val client = client()

        client.connect()

        assertTrue(client.listTools().size == 2)
    }

    @Test
    fun `the session id and negotiated version follow initialize on every later request`() = test {
        val client = client()
        client.connect()
        client.listTools()

        val (initialize, rest) = server.requests.first() to server.requests.drop(1)
        assertNull("no session before the server assigns one", initialize.header("Mcp-Session-Id"))
        rest.forEach {
            assertEquals("session-1", it.header("Mcp-Session-Id"))
            assertEquals("2025-06-18", it.header("MCP-Protocol-Version"))
        }
    }

    @Test
    fun `a stateless server without a session id is fine`() = test {
        server.sessionId = null
        val client = client()
        client.connect()
        client.listTools()

        assertTrue(server.requests.none { it.header("Mcp-Session-Id") != null })
    }

    @Test
    fun `a bearer token is sent on every request, and nothing when auth is none`() = test {
        client(headers = mapOf("Authorization" to "Bearer hf_testtoken123")).apply { connect(); listTools() }
        assertTrue(server.requests.all { it.header("Authorization") == "Bearer hf_testtoken123" })

        server.requests.clear()
        client().apply { connect(); listTools() }
        assertTrue(server.requests.all { it.header("Authorization") == null })
    }

    @Test
    fun `a tool call streams progress then returns the image, seed and prompt blocks`() = test {
        val client = client()
        client.connect()
        val progress = mutableListOf<McpProgress>()

        val result = client.callTool("flux_3_image_klein_infer", JsonObject(mapOf("prompt" to JsonPrimitive("koi")))) { progress += it }

        assertEquals(listOf(0.0, 1.0, 2.0), progress.map { it.progress })
        assertEquals("In queue. Position 0 out of 1.", progress.first().message)
        val image = result.content.first() as ToolContent.Image
        assertEquals("image/png", image.mimeType)
        assertEquals(StreamableMcpServer.TINY_PNG_BASE64, image.data)
        assertEquals(listOf("7", "a glass koi, studio lighting"), result.content.drop(2).map { (it as ToolContent.Text).text })
        val call = server.requestsFor("tools/call").single().message!!["params"]!!.jsonObject
        assertTrue("a progress token asks the server for progress", call["_meta"]!!.jsonObject.containsKey("progressToken"))
    }

    @Test
    fun `401 and 403 are authentication failures, never transport fallbacks`() = test {
        for (code in listOf(401, 403)) {
            server.override = { if (it.method == "initialize") MockResponse().setResponseCode(code) else null }
            val error = expectFailure<VisionStudioException.AuthenticationException> { client().connect() }
            assertEquals(code, error.httpCode)
            assertTrue("no GET fallback after an auth failure", server.requests.none { it.request.method == "GET" })
            server.requests.clear()
        }
    }

    @Test
    fun `a wrong path is reported as not found, not as a transport problem`() = test {
        server.override = { MockResponse().setResponseCode(404).setBody("Not Found") }

        val error = expectFailure<VisionStudioException.ProviderException> { client().connect() }

        assertEquals(404, error.httpCode)
        assertEquals("AUTO tried Streamable HTTP, then the legacy GET", listOf("POST", "GET"), server.requests.map { it.request.method })
    }

    @Test
    fun `a forced Streamable HTTP client explains a server that refuses it`() = test {
        server.override = { if (it.request.method == "POST") MockResponse().setResponseCode(405) else null }

        val error = expectFailure<VisionStudioException.UnsupportedTransportException> {
            client(transport = McpTransport.STREAMABLE_HTTP).connect()
        }

        assertTrue(error.message!!.contains("HTTP 405"))
    }

    @Test
    fun `a legacy SSE client pointed at a Streamable-HTTP-only server gets a transport error`() = test {
        // Gradio's /gradio_api/mcp/ answers a GET with a 405 here (the live Space holds it open).
        val error = expectFailure<VisionStudioException.UnsupportedTransportException> {
            client(transport = McpTransport.SSE).connect()
        }

        assertTrue(error.message!!.contains("Streamable HTTP"))
    }

    @Test
    fun `a JSON-RPC error to initialize surfaces its code`() = test {
        server.override = { if (it.method == "initialize") MockResponse().setHeader("Content-Type", "application/json").setBody(StreamableMcpServer.error(it.id!!, -32602, "Bad params").single()) else null }

        val error = expectFailure<VisionStudioException.ProtocolException> { client().connect() }

        assertEquals(-32602, error.rpcErrorCode)
    }

    @Test
    fun `an unknown protocol revision is refused`() = test {
        server.protocolVersion = "2099-01-01"
        val error = expectFailure<VisionStudioException.ProtocolException> { client().connect() }
        assertTrue(error.message!!.contains("2099-01-01"))
    }

    @Test
    fun `an older revision the client supports is accepted`() = test {
        server.protocolVersion = "2025-03-26"
        val client = client()
        client.connect()
        client.listTools()
        assertEquals("2025-03-26", server.requestsFor("tools/list").single().header("MCP-Protocol-Version"))
    }

    @Test
    fun `an expired session is reported so the caller reconnects`() = test {
        val client = client()
        client.connect()
        server.override = { if (it.method == "tools/list") MockResponse().setResponseCode(404) else null }

        val error = expectFailure<VisionStudioException.NetworkException> { client.listTools() }

        assertTrue(error.message!!.contains("session expired"))
    }

    @Test
    fun `closing ends the session with a DELETE carrying its id`() = test {
        val client = client()
        client.connect()

        client.close()

        val delete = server.requests.single { it.request.method == "DELETE" }
        assertEquals("session-1", delete.header("Mcp-Session-Id"))
    }

    @Test
    fun `a server that never answers times out`() = test {
        server.override = { if (it.method == "tools/list") MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else null }
        val client = client(requestTimeoutMillis = 500)
        client.connect()

        expectFailure<VisionStudioException.TimeoutException> { client.listTools() }
    }

    @Test
    fun `nothing listening is a network failure`() = test {
        val port = ServerSocket(0).use { it.localPort }

        val error = expectFailure<VisionStudioException.NetworkException> {
            client(url = "http://127.0.0.1:$port/gradio_api/mcp/", okHttp = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()).connect()
        }

        assertTrue(generateSequence(error as Throwable) { it.cause }.any { it is java.net.ConnectException })
    }

    @Test
    fun `notifications inside a response stream are delivered before the result`() = test {
        val client = client()
        client.connect()
        server.override = { recorded ->
            if (recorded.method == "tools/list") {
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{\"level\":\"info\",\"data\":\"hi\"}}\n\n" +
                        "event: message\ndata: ${StreamableMcpServer.rpcResult(recorded.id!!, "{\"tools\":[]}")}\n\n",
                )
            } else {
                null
            }
        }

        coroutineScope {
        val notification = async(start = CoroutineStart.UNDISPATCHED) { client.notifications.first { it.method == "notifications/message" } }

        assertEquals(0, client.listTools().size)
        assertEquals("notifications/message", notification.await().method)
        }
    }
}
