package com.visionstudio.core.network

import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.model.AuthScheme
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.core.model.RestDialect
import com.visionstudio.core.network.engine.EngineOutput
import com.visionstudio.core.network.engine.EngineProgress
import com.visionstudio.core.network.engine.EngineRequest
import com.visionstudio.core.network.engine.Submission
import com.visionstudio.core.network.engine.decodePreviewPayload
import com.visionstudio.core.network.engine.rest.CustomRestEngine
import com.visionstudio.core.network.engine.rest.OpenAiCompatibleEngine
import com.visionstudio.core.network.http.RestHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class StreamingEnginesTest {

    private lateinit var server: MockWebServer
    private val http = RestHttpClient(OkHttpClient(), Dispatchers.IO, Dispatchers.Default)
    private val params = GenerationParameters(prompt = "glass orchid", modelId = "m")

    // Minimal valid magic headers; enough for MIME sniffing.
    private val jpegB64 = Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2))
    private val pngB64 = Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    private fun sse(vararg frames: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(frames.joinToString("") { it.trimEnd('\n') + "\n\n" })

    @Test
    fun `custom engine streams progress previews and final result`() = runBlocking {
        server.enqueue(
            sse(
                "data: {\"progress\":0.25,\"preview\":\"$jpegB64\",\"status\":\"denoising\"}",
                ": keep-alive",
                "data: {\"step\":15,\"total_steps\":20}",
                "event: result\ndata: {\"images\":[{\"url\":\"https://cdn.example.com/final.png\"}]}",
            ),
        )
        val config = EngineConfig(EngineSlot.TEXT_TO_IMAGE, ProviderProtocol.REST, server.url("/gen").toString(), "m", RestDialect.CUSTOM, AuthScheme.NONE)
        val updates = mutableListOf<EngineProgress>()

        val result = CustomRestEngine(config, null, http, Dispatchers.IO)
            .submit(EngineRequest(EngineSlot.TEXT_TO_IMAGE, params)) { updates += it } as Submission.Completed

        assertTrue(server.takeRequest().getHeader("Accept")!!.contains("text/event-stream"))
        assertEquals(listOf(0.25f, 0.75f), updates.map { it.fraction })
        assertEquals("image/jpeg", updates[0].preview!!.mimeType)
        assertEquals("denoising", updates[0].message)
        assertNull(updates[1].preview)
        assertEquals("https://cdn.example.com/final.png", (result.outputs.single() as EngineOutput.Remote).url)
    }

    @Test
    fun `custom engine still accepts a plain JSON response`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"output":["https://x.dev/a.png"]}"""))
        val config = EngineConfig(EngineSlot.TEXT_TO_IMAGE, ProviderProtocol.REST, server.url("/gen").toString(), "m", RestDialect.CUSTOM, AuthScheme.NONE)

        val result = CustomRestEngine(config, null, http, Dispatchers.IO).submit(EngineRequest(EngineSlot.TEXT_TO_IMAGE, params))

        assertEquals(1, (result as Submission.Completed).outputs.size)
    }

    @Test
    fun `stream error event surfaces ProviderException`() = runBlocking {
        server.enqueue(sse("data: {\"progress\":10}", "event: error\ndata: {\"error\":\"CUDA out of memory\"}"))
        val config = EngineConfig(EngineSlot.TEXT_TO_IMAGE, ProviderProtocol.REST, server.url("/gen").toString(), "m", RestDialect.CUSTOM, AuthScheme.NONE)

        val error = runCatching {
            CustomRestEngine(config, null, http, Dispatchers.IO).submit(EngineRequest(EngineSlot.TEXT_TO_IMAGE, params))
        }.exceptionOrNull()

        assertTrue(error is VisionStudioException.ProviderException)
        assertEquals("CUDA out of memory", (error as VisionStudioException.ProviderException).providerMessage)
    }

    @Test
    fun `stream ending without result is a protocol error`() = runBlocking {
        server.enqueue(sse("data: {\"progress\":0.5}"))
        val config = EngineConfig(EngineSlot.TEXT_TO_IMAGE, ProviderProtocol.REST, server.url("/gen").toString(), "m", RestDialect.CUSTOM, AuthScheme.NONE)

        val error = runCatching {
            CustomRestEngine(config, null, http, Dispatchers.IO).submit(EngineRequest(EngineSlot.TEXT_TO_IMAGE, params))
        }.exceptionOrNull()

        assertTrue(error is VisionStudioException.ProtocolException)
    }

    @Test
    fun `openai partial image stream maps to previews then final image`() = runBlocking {
        server.enqueue(
            sse(
                "event: image_generation.partial_image\ndata: {\"type\":\"image_generation.partial_image\",\"b64_json\":\"$pngB64\",\"partial_image_index\":0}",
                "event: image_generation.partial_image\ndata: {\"type\":\"image_generation.partial_image\",\"b64_json\":\"$pngB64\",\"partial_image_index\":1}",
                "event: image_generation.completed\ndata: {\"type\":\"image_generation.completed\",\"b64_json\":\"$pngB64\"}",
            ),
        )
        // Point an "official" host at the mock server so the streaming branch is exercised.
        val proxied = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            val redirected = original.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            chain.proceed(original.newBuilder().url(redirected).build())
        }.build()
        val engine = OpenAiCompatibleEngine(
            EngineConfig(EngineSlot.TEXT_TO_IMAGE, ProviderProtocol.REST, "https://api.openai.com/v1", "gpt-image-1"),
            "sk",
            RestHttpClient(proxied, Dispatchers.IO, Dispatchers.Default),
            Dispatchers.IO,
        )
        val updates = mutableListOf<EngineProgress>()

        val result = engine.submit(EngineRequest(EngineSlot.TEXT_TO_IMAGE, params)) { updates += it } as Submission.Completed

        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("true", sent["stream"]!!.jsonPrimitive.content)
        assertEquals(2, updates.size)
        assertTrue(updates.all { it.preview?.mimeType == "image/png" })
        assertEquals(1f / 3f, updates[0].fraction!!, 0.001f)
        assertEquals(pngB64, (result.outputs.single() as EngineOutput.Inline).base64)
    }

    @Test
    fun `preview payload decoding sniffs mime and rejects garbage`() {
        assertEquals("image/jpeg", decodePreviewPayload(jpegB64)!!.mimeType)
        assertEquals("image/webp", decodePreviewPayload("data:image/webp;base64,$jpegB64")!!.mimeType)
        assertNull(decodePreviewPayload("not base64 !!"))
        assertNull(decodePreviewPayload(Base64.getEncoder().encodeToString("hello".toByteArray())))
        assertArrayEquals(Base64.getDecoder().decode(pngB64), decodePreviewPayload(pngB64)!!.bytes)
    }

}
