package com.visionstudio.core.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.model.AuthScheme
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.core.network.engine.BinaryPayload
import com.visionstudio.core.network.engine.EngineRequest
import com.visionstudio.core.network.engine.EngineOutput
import com.visionstudio.core.network.engine.Submission
import com.visionstudio.core.network.engine.rest.OpenAiCompatibleEngine
import com.visionstudio.core.network.engine.rest.OpenAiMask
import com.visionstudio.core.network.http.RestHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OpenAiMaskTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    /** 8×4 mask: left half white (repaint), right half black (keep). */
    private fun halfMaskPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        for (y in 0 until 4) for (x in 0 until 8) bitmap.setPixel(x, y, if (x < 4) WHITE else BLACK)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bitmap.recycle() }
    }

    private fun decode(png: ByteArray): Bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)

    @Test
    fun `white becomes transparent and black becomes opaque`() {
        val converted = decode(OpenAiMask.toAlphaMask(halfMaskPng()))

        assertEquals(8, converted.width)
        assertEquals(4, converted.height)
        for (y in 0 until 4) {
            for (x in 0 until 8) {
                val alpha = converted.getPixel(x, y) ushr 24
                if (x < 4) assertEquals("repaint region must be transparent at ($x,$y)", 0, alpha)
                else assertEquals("kept region must be opaque at ($x,$y)", 255, alpha)
            }
        }
    }

    @Test
    fun `undecodable input is a validation error`() {
        val error = runCatching { OpenAiMask.toAlphaMask(byteArrayOf(1, 2, 3)) }.exceptionOrNull()
        assertTrue(error is VisionStudioException.ValidationException)
    }

    @Test
    fun `openai edit sends the converted mask as a png part`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":[{"b64_json":"aGk="}]}"""))
        val config = EngineConfig(EngineSlot.IMAGE_EDIT, ProviderProtocol.REST, server.url("/v1").toString().trimEnd('/'), "gpt-image-1", authScheme = AuthScheme.BEARER)
        val http = RestHttpClient(OkHttpClient(), Dispatchers.IO, Dispatchers.Default)
        val engine = OpenAiCompatibleEngine(config, "sk", http, Dispatchers.IO, Dispatchers.Default)
        val source = BinaryPayload(halfMaskPng(), "image/png")
        val mask = BinaryPayload(halfMaskPng(), "image/png")

        val result = engine.submit(
            EngineRequest(EngineSlot.IMAGE_EDIT, GenerationParameters(prompt = "a red balloon", modelId = "gpt-image-1"), source, mask),
        ) as Submission.Completed

        val recorded = server.takeRequest()
        assertEquals("/v1/images/edits", recorded.path)
        val body = recorded.body.readByteArray()
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.contains("name=\"mask\"; filename=\"mask.png\""))
        assertTrue(text.contains("name=\"image\"; filename=\"source.png\""))
        // The mask part must differ from the original B/W mask because it was converted.
        val original = String(halfMaskPng(), Charsets.ISO_8859_1)
        val maskPart = text.substringAfter("name=\"mask\"").substringAfter("\r\n\r\n").substringBefore("\r\n--")
        assertTrue(maskPart.startsWith("\u0089PNG"))
        assertTrue(maskPart != original)
        assertTrue(result.outputs.single() is EngineOutput.Inline)
    }

    private companion object {
        const val WHITE = -0x1
        const val BLACK = -0x1000000
    }
}
