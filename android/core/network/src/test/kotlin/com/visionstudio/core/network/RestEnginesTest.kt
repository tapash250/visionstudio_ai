package com.visionstudio.core.network

import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.model.AspectRatio
import com.visionstudio.core.model.AuthScheme
import com.visionstudio.core.model.CameraMotion
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.core.model.RestDialect
import com.visionstudio.core.network.engine.BinaryPayload
import com.visionstudio.core.network.engine.EngineOutput
import com.visionstudio.core.network.engine.EngineRequest
import com.visionstudio.core.network.engine.JobStatus
import com.visionstudio.core.network.engine.RemoteCancelResult
import com.visionstudio.core.network.engine.RemoteJob
import com.visionstudio.core.network.engine.Submission
import com.visionstudio.core.network.engine.authHeaders
import com.visionstudio.core.network.engine.extractOutputs
import com.visionstudio.core.network.engine.rest.CustomRestEngine
import com.visionstudio.core.network.engine.rest.FalQueueEngine
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestEnginesTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val http = RestHttpClient(OkHttpClient(), Dispatchers.IO, Dispatchers.Default)

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    private fun base(path: String = "") = server.url("/$path").toString().trimEnd('/')

    private fun config(
        slot: EngineSlot,
        dialect: RestDialect,
        auth: AuthScheme = AuthScheme.BEARER,
        modelId: String = "model-x",
    ) = EngineConfig(slot, ProviderProtocol.REST, base("v1"), modelId, dialect, auth)

    private val params = GenerationParameters(
        prompt = "obsidian koi in neon rain",
        negativePrompt = "blurry",
        aspectRatio = AspectRatio.LANDSCAPE_16_9,
        steps = 28,
        guidanceScale = 6.5f,
        seed = 42,
        modelId = "model-x",
    )

    private fun body(json: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(json)

    @Test
    fun `auth headers follow scheme`() {
        val base = config(EngineSlot.TEXT_TO_IMAGE, RestDialect.CUSTOM)
        assertEquals(mapOf("Authorization" to "Bearer k"), base.authHeaders("k"))
        assertEquals(mapOf("Authorization" to "Key k"), base.copy(authScheme = AuthScheme.KEY).authHeaders("k"))
        assertEquals(
            mapOf("x-api-key" to "k"),
            base.copy(authScheme = AuthScheme.CUSTOM_HEADER, customAuthHeader = "x-api-key").authHeaders("k"),
        )
        assertEquals(emptyMap<String, String>(), base.authHeaders("  "))
    }

    @Test
    fun `openai generation posts expected payload and decodes b64 output`() = runBlocking {
        server.enqueue(body("""{"created":1,"data":[{"b64_json":"aGVsbG8="}]}"""))
        val engine = OpenAiCompatibleEngine(config(EngineSlot.TEXT_TO_IMAGE, RestDialect.OPENAI_COMPATIBLE), "sk-1", http, Dispatchers.IO)

        val submission = engine.submit(EngineRequest(EngineSlot.TEXT_TO_IMAGE, params)) as Submission.Completed

        val recorded = server.takeRequest()
        assertEquals("/v1/images/generations", recorded.path)
        assertEquals("Bearer sk-1", recorded.getHeader("Authorization"))
        val sent = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("model-x", sent["model"]!!.jsonPrimitive.content)
        assertEquals("1024x576", sent["size"]!!.jsonPrimitive.content)
        val output = submission.outputs.single() as EngineOutput.Inline
        assertEquals("hello", output.decode().toString(Charsets.UTF_8))
    }

    @Test
    fun `openai probe reports listed model`() = runBlocking {
        server.enqueue(body("""{"data":[{"id":"model-x"},{"id":"other"}]}"""))
        val probe = OpenAiCompatibleEngine(config(EngineSlot.TEXT_TO_IMAGE, RestDialect.OPENAI_COMPATIBLE), "sk", http, Dispatchers.IO)
            .testConnection()
        assertTrue(probe.success)
        assertEquals("Connected · model available", probe.headline)
        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test
    fun `401 probe is reported as authentication failure`() = runBlocking {
        server.enqueue(body("""{"error":{"message":"Incorrect API key provided"}}""").setResponseCode(401))
        val probe = OpenAiCompatibleEngine(config(EngineSlot.TEXT_TO_IMAGE, RestDialect.OPENAI_COMPATIBLE), "bad", http, Dispatchers.IO)
            .testConnection()
        assertFalse(probe.success)
        assertEquals("Authentication failed (HTTP 401)", probe.headline)
        assertTrue(probe.details.toString(), probe.details.any { it.contains("Incorrect API key") })
    }

    @Test
    fun `openai video job is queued then polled to completion`() = runBlocking {
        server.enqueue(body("""{"id":"video_123","status":"queued","progress":0}"""))
        server.enqueue(body("""{"id":"video_123","status":"in_progress","progress":40}"""))
        server.enqueue(body("""{"id":"video_123","status":"completed","progress":100}"""))
        val engine = OpenAiCompatibleEngine(config(EngineSlot.VIDEO_STUDIO, RestDialect.OPENAI_COMPATIBLE), "sk", http, Dispatchers.IO)

        val job = (engine.submit(EngineRequest(EngineSlot.VIDEO_STUDIO, params.copy(durationSeconds = 8))) as Submission.Queued).job

        assertEquals("/v1/videos", server.takeRequest().path)
        assertEquals(0.4f, (engine.poll(job) as JobStatus.InProgress).progress)
        val done = engine.poll(job) as JobStatus.Completed
        assertTrue((done.outputs.single() as EngineOutput.Remote).url.endsWith("/v1/videos/video_123/content"))
    }

    @Test
    fun `fal submit uses Key auth and maps parameters`() = runBlocking {
        server.enqueue(
            body(
                """{"request_id":"r-1","status_url":"${base("v1")}/fal-ai/flux/requests/r-1/status","response_url":"${base("v1")}/fal-ai/flux/requests/r-1"}""",
            ),
        )
        val engine = FalQueueEngine(
            config(EngineSlot.TEXT_TO_IMAGE, RestDialect.FAL_QUEUE, AuthScheme.KEY, modelId = "fal-ai/flux"),
            "fal-key",
            http,
            Dispatchers.IO,
        )

        val job = (engine.submit(EngineRequest(EngineSlot.TEXT_TO_IMAGE, params)) as Submission.Queued).job

        val recorded = server.takeRequest()
        assertEquals("/v1/fal-ai/flux", recorded.path)
        assertEquals("Key fal-key", recorded.getHeader("Authorization"))
        val sent = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(28, sent["num_inference_steps"]!!.jsonPrimitive.content.toInt())
        assertEquals("1024", sent["image_size"]!!.jsonObject["width"]!!.jsonPrimitive.content)
        assertEquals("blurry", sent["negative_prompt"]!!.jsonPrimitive.content)
        assertEquals("r-1", job.id)
    }

    @Test
    fun `fal poll walks queue states and fetches result`() = runBlocking {
        val engine = FalQueueEngine(
            config(EngineSlot.TEXT_TO_IMAGE, RestDialect.FAL_QUEUE, AuthScheme.KEY, modelId = "fal-ai/flux"),
            "k",
            http,
            Dispatchers.IO,
        )
        val job = com.visionstudio.core.network.engine.RemoteJob("r-1", base("status"), base("result"))
        server.enqueue(body("""{"status":"IN_QUEUE","queue_position":3}"""))
        server.enqueue(body("""{"status":"COMPLETED"}"""))
        server.enqueue(body("""{"images":[{"url":"https://v3.fal.media/out.png","content_type":"image/png"}],"seed":42}"""))

        assertEquals(3, (engine.poll(job) as JobStatus.InProgress).queuePosition)
        val done = engine.poll(job) as JobStatus.Completed
        assertEquals("https://v3.fal.media/out.png", (done.outputs.single() as EngineOutput.Remote).url)
    }

    @Test
    fun `fal model failure on result fetch becomes Failed status`() = runBlocking {
        val engine = FalQueueEngine(
            config(EngineSlot.IMAGE_EDIT, RestDialect.FAL_QUEUE, AuthScheme.KEY),
            "k",
            http,
            Dispatchers.IO,
        )
        server.enqueue(body("""{"status":"COMPLETED"}"""))
        server.enqueue(body("""{"detail":"Image failed safety check"}""").setResponseCode(422))

        val status = engine.poll(com.visionstudio.core.network.engine.RemoteJob("r", base("s"), base("r")))

        assertEquals(JobStatus.Failed("Image failed safety check", retryable = false), status)
    }

    @Test
    fun `fal edit without source image is rejected before network`() = runBlocking {
        val engine = FalQueueEngine(config(EngineSlot.IMAGE_EDIT, RestDialect.FAL_QUEUE, AuthScheme.KEY), "k", http, Dispatchers.IO)
        val error = runCatching { engine.submit(EngineRequest(EngineSlot.IMAGE_EDIT, params)) }.exceptionOrNull()
        assertTrue(error is VisionStudioException.ValidationException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `custom engine handles job envelope and camera directive`() = runBlocking {
        server.enqueue(body("""{"job_id":"j9","status":"queued"}"""))
        server.enqueue(body("""{"status":"running","progress":55}"""))
        server.enqueue(body("""{"status":"succeeded","output":["https://cdn.example.com/clip.mp4"]}"""))
        val engine = CustomRestEngine(config(EngineSlot.VIDEO_STUDIO, RestDialect.CUSTOM), "k", http, Dispatchers.IO)

        val job = (
            engine.submit(EngineRequest(EngineSlot.VIDEO_STUDIO, params.copy(cameraMotion = CameraMotion.ORBIT))) as Submission.Queued
            ).job

        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(sent["prompt"]!!.jsonPrimitive.content.endsWith("Camera movement: orbit."))
        assertTrue(job.statusUrl.endsWith("/v1/j9"))
        assertEquals(0.55f, (engine.poll(job) as JobStatus.InProgress).progress)
        val done = engine.poll(job) as JobStatus.Completed
        assertEquals("video/mp4", done.outputs.single().mimeType)
    }

    @Test
    fun `fal cancel PUTs the cancel url with Key auth and maps the documented answers`() = runBlocking {
        val engine = FalQueueEngine(config(EngineSlot.VIDEO_STUDIO, RestDialect.FAL_QUEUE, AuthScheme.KEY, "fal-ai/kling"), "fk", http, Dispatchers.IO)
        val job = RemoteJob("req-1", base("v1/fal-ai/kling/requests/req-1/status"), cancelUrl = base("v1/fal-ai/kling/requests/req-1/cancel"))
        server.enqueue(body("""{"status":"CANCELLATION_REQUESTED"}""").setResponseCode(202))
        server.enqueue(body("""{"status":"ALREADY_COMPLETED"}""").setResponseCode(400))
        server.enqueue(body("""{"status":"NOT_FOUND"}""").setResponseCode(404))

        assertEquals(RemoteCancelResult.REQUESTED, engine.cancel(job))
        assertEquals(RemoteCancelResult.ALREADY_FINISHED, engine.cancel(job))
        assertEquals(RemoteCancelResult.NOT_FOUND, engine.cancel(job))

        val first = server.takeRequest()
        assertEquals("PUT", first.method)
        assertEquals("/v1/fal-ai/kling/requests/req-1/cancel", first.path)
        assertEquals("Key fk", first.getHeader("Authorization"))
    }

    @Test
    fun `fal cancel never sends the key to a cancel url on another host`() = runBlocking {
        val engine = FalQueueEngine(config(EngineSlot.VIDEO_STUDIO, RestDialect.FAL_QUEUE, AuthScheme.KEY, "fal-ai/kling"), "fk", http, Dispatchers.IO)
        server.enqueue(MockResponse().setResponseCode(202))
        val job = RemoteJob("req-7", base("v1/s"), cancelUrl = "https://attacker.example/steal")

        assertEquals(RemoteCancelResult.REQUESTED, engine.cancel(job))

        assertEquals("rebuilt on the queue's own host", "/v1/fal-ai/kling/requests/req-7/cancel", server.takeRequest().path)
    }

    @Test
    fun `fal cancel surfaces auth failures as typed errors`() = runBlocking {
        val engine = FalQueueEngine(config(EngineSlot.VIDEO_STUDIO, RestDialect.FAL_QUEUE, AuthScheme.KEY, "fal-ai/kling"), "bad", http, Dispatchers.IO)
        server.enqueue(body("""{"detail":"invalid key"}""").setResponseCode(401))

        val failure = runCatching { engine.cancel(RemoteJob("r", base("v1/s"))) }.exceptionOrNull()

        assertTrue("got $failure", failure is VisionStudioException.AuthenticationException)
    }

    @Test
    fun `custom engine cancels only through a cancel_url its envelope announced`() = runBlocking {
        val engine = CustomRestEngine(config(EngineSlot.VIDEO_STUDIO, RestDialect.CUSTOM), "ck", http, Dispatchers.IO)
        server.enqueue(body("""{"job_id":"j-1","status_url":"${base("v1/jobs/j-1")}","cancel_url":"${base("v1/jobs/j-1/cancel")}"}"""))
        val queued = engine.submit(EngineRequest(EngineSlot.VIDEO_STUDIO, params)) as Submission.Queued
        server.takeRequest()
        assertEquals(base("v1/jobs/j-1/cancel"), queued.job.cancelUrl)

        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(RemoteCancelResult.REQUESTED, engine.cancel(queued.job))
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/v1/jobs/j-1/cancel", sent.path)
        assertEquals("Bearer ck", sent.getHeader("Authorization"))

        server.enqueue(MockResponse().setResponseCode(409))
        assertEquals(RemoteCancelResult.ALREADY_FINISHED, engine.cancel(queued.job))
    }

    @Test
    fun `custom engine without a cancel_url, or with a foreign one, sends nothing`() = runBlocking {
        val engine = CustomRestEngine(config(EngineSlot.VIDEO_STUDIO, RestDialect.CUSTOM), "ck", http, Dispatchers.IO)

        assertEquals(RemoteCancelResult.UNSUPPORTED, engine.cancel(RemoteJob("j", base("v1/jobs/j"))))
        assertEquals(RemoteCancelResult.UNSUPPORTED, engine.cancel(RemoteJob("j", base("v1/jobs/j"), cancelUrl = "https://elsewhere.example/c")))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `openai has no documented cancel, so nothing is sent`() = runBlocking {
        val engine = OpenAiCompatibleEngine(config(EngineSlot.VIDEO_STUDIO, RestDialect.OPENAI_COMPATIBLE), "sk", http, Dispatchers.IO)

        assertEquals(RemoteCancelResult.UNSUPPORTED, engine.cancel(RemoteJob("video_1", base("v1/videos/video_1"))))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `remote download only sends credentials to the provider host`() = runBlocking {
        server.enqueue(MockResponse().setBody("PNGDATA"))
        val engine = CustomRestEngine(config(EngineSlot.TEXT_TO_IMAGE, RestDialect.CUSTOM), "secret", http, Dispatchers.IO)
        val target = temp.root.resolve("out/image.png")

        val written = engine.downloadTo(EngineOutput.Remote(server.url("/files/a.png").toString(), "image/png"), target)

        assertEquals(7L, written)
        assertEquals("PNGDATA", target.readText())
        assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization"))
        assertFalse(temp.root.resolve("out/image.png.part").exists())
    }

    @Test
    fun `output extraction handles common provider shapes`() {
        val json = Json.parseToJsonElement(
            """{"data":[{"url":"https://a.com/1.png"}],"video":{"url":"https://a.com/v.mp4"},"output":["data:image/webp;base64,AAAA"]}""",
        )
        val outputs = extractOutputs(json, "image/png")
        assertEquals(listOf("image/png", "video/mp4", "image/webp"), outputs.map { it.mimeType })
    }

    @Test
    fun `binary payload data uri`() {
        assertEquals("data:image/png;base64,AQID", BinaryPayload(byteArrayOf(1, 2, 3), "image/png").dataUri())
    }
}
