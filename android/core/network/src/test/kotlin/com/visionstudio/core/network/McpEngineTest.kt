package com.visionstudio.core.network

import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.mcp.McpClientFactory
import com.visionstudio.core.mcp.McpServerConfig
import com.visionstudio.core.model.AuthScheme
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.core.network.engine.ConnectionProbe
import com.visionstudio.core.network.engine.EngineOutput
import com.visionstudio.core.network.engine.EngineRequest
import com.visionstudio.core.network.engine.Submission
import com.visionstudio.core.network.engine.authHeaders
import com.visionstudio.core.network.engine.mcp.McpGenerationEngine
import com.visionstudio.core.network.engine.redact
import com.visionstudio.core.network.http.RestHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * McpGenerationEngine over the real MCP client and OkHttp engine against a local Streamable HTTP
 * server shaped like the FLUX 3 Image Klein Space: connection checks and their diagnostics, the
 * arguments sent, and parsing of the Space's (image, seed, prompt) tuple.
 */
class McpEngineTest {

    private lateinit var server: MockWebServer
    private val posted: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    private val authHeaders: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private var tools = listOf(TOOL_LOAD_INITIAL, TOOL_INFER)
    private var callContent = gradioTuple(fileUrl = "https://example.hf.space/gradio_api/file=/tmp/gradio/a/image.png")
    private var callIsError = false
    private var override: (RecordedRequest, JsonObject?) -> MockResponse? = { _, _ -> null }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val message = runCatching { Json.parseToJsonElement(request.body.readUtf8()).jsonObject }.getOrNull()
                message?.let { posted += it }
                authHeaders += request.getHeader("Authorization")
                override(request, message)?.let { return it }
                if (request.method != "POST") return MockResponse().setResponseCode(405)
                val id = message?.get("id") ?: return MockResponse().setResponseCode(202)
                val result = when (message["method"]?.jsonPrimitive?.content) {
                    "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"FLUX 3 Image Klein","version":"1.30.0"}}"""
                    "tools/list" -> """{"tools":[${tools.joinToString(",")}]}"""
                    "tools/call" -> """{"content":$callContent,"isError":$callIsError}"""
                    else -> "{}"
                }
                return MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("event: message\r\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}\r\n\r\n")
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun config(url: String = server.url("/gradio_api/mcp/").toString(), auth: AuthScheme = AuthScheme.NONE) = EngineConfig(
        slot = EngineSlot.TEXT_TO_IMAGE,
        protocol = ProviderProtocol.MCP,
        endpointUrl = url,
        modelId = "black-forest-labs/flux-3-image-klein",
        authScheme = auth,
        mcpToolName = "flux_3_image_klein_infer",
        requestTimeoutSeconds = 5,
    )

    private fun engine(config: EngineConfig = config(), apiKey: String? = null, okHttp: OkHttpClient = OkHttpClient()): McpGenerationEngine {
        val client = McpClientFactory(okHttp, Dispatchers.IO, Dispatchers.Default).create(
            McpServerConfig(url = config.endpointUrl, headers = config.authHeaders(apiKey), requestTimeoutMillis = config.requestTimeoutSeconds * 1_000L),
        )
        return McpGenerationEngine(config, apiKey, client, RestHttpClient(okHttp, Dispatchers.IO, Dispatchers.Default), Dispatchers.IO, Dispatchers.Default)
    }

    private fun probe(engine: McpGenerationEngine): ConnectionProbe = runBlocking {
        try {
            engine.testConnection()
        } finally {
            engine.close()
        }
    }

    private fun submit(engine: McpGenerationEngine, parameters: GenerationParameters = GenerationParameters(prompt = "a glass koi", modelId = "m")): Submission.Completed =
        runBlocking {
            try {
                engine.submit(EngineRequest(EngineSlot.TEXT_TO_IMAGE, parameters)) as Submission.Completed
            } finally {
                engine.close()
            }
        }

    // region connection check

    @Test
    fun `a check passes only after initialize and tools-list find the configured tool`() {
        val result = probe(engine())

        assertTrue(result.success)
        assertEquals("Connected · tool ready", result.headline)
        assertTrue(result.details.first(), result.details.first().contains("Streamable HTTP · MCP 2025-06-18"))
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), posted.map { it["method"]!!.jsonPrimitive.content })
    }

    @Test
    fun `a missing tool fails the check and lists what the server has`() {
        tools = listOf(TOOL_LOAD_INITIAL)

        val result = probe(engine())

        assertFalse(result.success)
        assertEquals("Connected · tool not found", result.headline)
        assertTrue(result.details.any { it.contains("flux_3_image_klein__load_initial") })
    }

    @Test
    fun `bearer auth sends the token and none sends no Authorization header`() {
        assertTrue(probe(engine(config(auth = AuthScheme.BEARER), apiKey = "hf_abcdefgh12345678")).success)
        assertTrue(authHeaders.all { it == "Bearer hf_abcdefgh12345678" })

        authHeaders.clear()
        assertTrue(probe(engine(config(auth = AuthScheme.NONE))).success)
        assertTrue(authHeaders.all { it == null })
    }

    @Test
    fun `an unresolvable host is reported as host not found`() {
        val noDns = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("Unable to resolve host \"$hostname\"")
        }).build()

        val result = probe(engine(config(url = "https://black-forest-labs-flux-3-image-klein.hf.space/gradio_api/mcp/"), okHttp = noDns))

        assertEquals("Host not found", result.headline)
    }

    @Test
    fun `an untrusted certificate is reported as a TLS trust failure`() {
        useHttps(HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build(), ca = null)

        val result = probe(engine(config(url = server.url("/gradio_api/mcp/").toString().replace("127.0.0.1", "localhost"))))

        assertEquals("TLS certificate not trusted", result.headline)
    }

    @Test
    fun `a certificate for another host is reported as a TLS hostname mismatch`() {
        val ca = HeldCertificate.Builder().certificateAuthority(0).build()
        useHttps(HeldCertificate.Builder().signedBy(ca).addSubjectAlternativeName("other.example").build(), ca)
        val trusting = HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(trusting.sslSocketFactory(), trusting.trustManager).build()

        val result = probe(engine(config(url = server.url("/gradio_api/mcp/").toString().replace("127.0.0.1", "localhost")), okHttp = client))

        assertEquals("TLS hostname mismatch", result.headline)
    }

    @Test
    fun `a server that never answers times out`() {
        override = { _, message -> if (message?.get("method")?.jsonPrimitive?.content == "initialize") MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else null }

        val result = probe(engine(config().copy(requestTimeoutSeconds = 1)))

        assertEquals("Connection timed out", result.headline)
    }

    @Test
    fun `a refused connection is reported as server unreachable`() {
        val port = ServerSocket(0).use { it.localPort }

        val result = probe(engine(config(url = "http://127.0.0.1:$port/gradio_api/mcp/"), okHttp = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()))

        assertEquals("Server unreachable", result.headline)
    }

    @Test
    fun `401 and 403 are authentication failures, and the token never appears in the details`() {
        for (code in listOf(401, 403)) {
            override = { _, _ -> MockResponse().setResponseCode(code).setBody("""{"error":"Invalid token hf_abcdefgh12345678 for Authorization: Bearer hf_abcdefgh12345678"}""") }

            val result = probe(engine(config(auth = AuthScheme.BEARER), apiKey = "hf_abcdefgh12345678"))

            assertEquals("Authentication failed (HTTP $code)", result.headline)
            assertTrue(result.details.none { it.contains("hf_abcdefgh12345678") })
        }
    }

    @Test
    fun `404 and 405 name the route problem`() {
        override = { _, _ -> MockResponse().setResponseCode(404).setBody("Not Found") }
        assertEquals("Endpoint not found (HTTP 404)", probe(engine()).headline)

        // POST 405 and GET 405: neither transport is served here.
        override = { _, _ -> MockResponse().setResponseCode(405) }
        assertEquals("Unsupported MCP transport", probe(engine()).headline)
    }

    @Test
    fun `an MCP initialize error is reported with its code`() {
        override = { _, message ->
            if (message?.get("method")?.jsonPrimitive?.content == "initialize") {
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"jsonrpc":"2.0","id":${message["id"]},"error":{"code":-32602,"message":"Unsupported protocol"}}""")
            } else {
                null
            }
        }

        val result = probe(engine())

        assertEquals("MCP error -32602", result.headline)
    }

    // endregion

    // region tool call

    @Test
    fun `the Gradio tuple yields exactly one image and the reported seed`() {
        val completed = submit(engine())

        val image = completed.outputs.single() as EngineOutput.Inline
        assertEquals("image/png", image.mimeType)
        assertEquals(TINY_PNG_BASE64, image.base64)
        assertEquals(7L, completed.reportedSeed)
    }

    @Test
    fun `only declared arguments are sent, and a fixed seed switches randomize_seed off`() {
        submit(engine(), GenerationParameters(prompt = "a glass koi", modelId = "m", seed = 42))

        val arguments = posted.single { it["method"]!!.jsonPrimitive.content == "tools/call" }["params"]!!.jsonObject["arguments"]!!.jsonObject
        assertEquals("a glass koi", arguments["prompt"]!!.jsonPrimitive.content)
        assertEquals("42", arguments["seed"]!!.jsonPrimitive.content)
        assertEquals("false", arguments["randomize_seed"]!!.jsonPrimitive.content)
        assertTrue(arguments.containsKey("width") && arguments.containsKey("height"))
        assertFalse("the model ID is not a tool argument here", arguments.containsKey("model") || arguments.containsKey("model_id"))
    }

    @Test
    fun `without a fixed seed, randomize_seed is left to the server`() {
        submit(engine())

        val arguments = posted.single { it["method"]!!.jsonPrimitive.content == "tools/call" }["params"]!!.jsonObject["arguments"]!!.jsonObject
        assertFalse(arguments.containsKey("randomize_seed") || arguments.containsKey("seed"))
    }

    @Test
    fun `a tuple whose image is only a Gradio file URL yields that URL`() {
        callContent = """[{"type":"text","text":"Image URL: https://example.hf.space/gradio_api/file=/tmp/gradio/a/image.webp"},{"type":"text","text":"12"},{"type":"text","text":"a koi"}]"""

        val completed = submit(engine())

        val remote = completed.outputs.single() as EngineOutput.Remote
        assertEquals("https://example.hf.space/gradio_api/file=/tmp/gradio/a/image.webp", remote.url)
        assertEquals(12L, completed.reportedSeed)
    }

    @Test
    fun `a Gradio FileData object in a text block yields its url`() {
        callContent = """[{"type":"text","text":"{\"path\":\"/tmp/gradio/b/out.png\",\"url\":\"https://example.hf.space/gradio_api/file=/tmp/gradio/b/out.png\",\"meta\":{\"_type\":\"gradio.FileData\"}}"}]"""

        val completed = submit(engine())

        assertEquals("https://example.hf.space/gradio_api/file=/tmp/gradio/b/out.png", (completed.outputs.single() as EngineOutput.Remote).url)
        assertNull("no bare integer block, so no seed", completed.reportedSeed)
    }

    @Test
    fun `a tool error is a provider failure with the tool's message`() {
        callIsError = true
        callContent = """[{"type":"text","text":"ZeroGPU quota exceeded"}]"""

        val error = runCatching { submit(engine()) }.exceptionOrNull()

        assertTrue("got $error", error is VisionStudioException.ProviderException)
        assertEquals("ZeroGPU quota exceeded", (error as VisionStudioException.ProviderException).providerMessage)
    }

    // endregion

    @Test
    fun `redaction removes keys and credential-shaped values`() {
        val text = redact("Authorization: Bearer abcdefgh12345678 token hf_zzzzzzzzzzzz url?api_key=secret1 my-key-value", listOf("my-key-value"))

        assertFalse(text, listOf("abcdefgh12345678", "hf_zzzzzzzzzzzz", "secret1", "my-key-value").any { it in text })
    }

    private fun useHttps(certificate: HeldCertificate, ca: HeldCertificate?) {
        server.shutdown()
        val dispatcher = server.dispatcher
        server = MockWebServer().apply { this.dispatcher = dispatcher }
        val builder = HandshakeCertificates.Builder()
        if (ca != null) builder.heldCertificate(certificate, ca.certificate) else builder.heldCertificate(certificate)
        server.useHttps(builder.build().sslSocketFactory(), false)
        server.start()
    }

    private companion object {
        const val TINY_PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
        const val TOOL_LOAD_INITIAL = """{"name":"flux_3_image_klein__load_initial","inputSchema":{"type":"object","properties":{}}}"""
        const val TOOL_INFER = """{"name":"flux_3_image_klein_infer","description":"Generate or edit images with FLUX 3 Image Klein.","inputSchema":{"type":"object","required":["prompt"],"properties":{"prompt":{"type":"string"},"input_images":{"type":"array"},"mode_choice":{"type":"string"},"seed":{"type":"number"},"randomize_seed":{"type":"boolean"},"width":{"type":"number"},"height":{"type":"number"},"num_inference_steps":{"type":"number"},"guidance_scale":{"type":"number"},"prompt_upsampling":{"type":"boolean"}}}}"""

        /** The block layout the live Space returns. */
        fun gradioTuple(fileUrl: String) =
            """[{"type":"image","data":"$TINY_PNG_BASE64","mimeType":"image/png"},{"type":"text","text":"Image URL: $fileUrl"},{"type":"text","text":"7"},{"type":"text","text":"Studio product photograph of a small glass koi"}]"""
    }
}
