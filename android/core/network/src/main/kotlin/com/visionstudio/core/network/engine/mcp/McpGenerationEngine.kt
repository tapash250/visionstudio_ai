package com.visionstudio.core.network.engine.mcp

import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.mcp.McpClient
import com.visionstudio.core.mcp.protocol.CallToolResult
import com.visionstudio.core.mcp.protocol.McpTool
import com.visionstudio.core.mcp.protocol.ToolContent
import com.visionstudio.core.network.engine.ConnectionProbe
import com.visionstudio.core.network.engine.EngineOutput
import com.visionstudio.core.network.engine.EngineProgress
import com.visionstudio.core.network.engine.EngineRequest
import com.visionstudio.core.network.engine.GenerationEngine
import com.visionstudio.core.network.engine.JobStatus
import com.visionstudio.core.network.engine.RemoteJob
import com.visionstudio.core.network.engine.Submission
import com.visionstudio.core.network.engine.authHeaders
import com.visionstudio.core.network.engine.extractOutputs
import com.visionstudio.core.network.engine.guessMime
import com.visionstudio.core.network.engine.headers
import com.visionstudio.core.network.engine.isSameHost
import com.visionstudio.core.network.engine.outputDimensions
import com.visionstudio.core.network.engine.parseDataUri
import com.visionstudio.core.network.engine.timed
import com.visionstudio.core.network.engine.toFailedProbe
import com.visionstudio.core.network.http.RestHttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Request
import java.io.File

/**
 * Adapts an MCP tool to [GenerationEngine]. Generation is a single `tools/call` on
 * [EngineConfig.mcpToolName]. Arguments are offered under common aliases and filtered to
 * those the tool's `inputSchema` declares, so strict servers never see unknown keys.
 */
class McpGenerationEngine(
    override val config: EngineConfig,
    apiKey: String?,
    private val client: McpClient,
    private val http: RestHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher,
) : GenerationEngine {

    private val toolName: String = config.mcpToolName
        ?: throw VisionStudioException.ValidationException("mcpToolName", "MCP engines need a tool name")
    private val auth = config.authHeaders(apiKey)
    private val secrets = listOfNotNull(apiKey?.takeIf { it.isNotBlank() })
    private val toolMutex = Mutex()
    private var cachedTool: McpTool? = null

    override suspend fun testConnection(): ConnectionProbe {
        var latency: Long? = null
        return runCatching {
            val (outcome, elapsed) = timed {
                val server = client.connect()
                server to client.listTools()
            }
            latency = elapsed
            val (server, tools) = outcome
            val tool = tools.firstOrNull { it.name == toolName }
            toolMutex.withLock { cachedTool = tool }
            val transport = client.activeTransport?.displayName ?: "MCP"
            ConnectionProbe(
                success = tool != null,
                headline = if (tool != null) "Connected · tool ready" else "Connected · tool not found",
                details = buildList {
                    add("${server.serverInfo.name} ${server.serverInfo.version} · $transport · MCP ${server.protocolVersion}")
                    add("${tools.size} tools exposed")
                    if (tool == null) {
                        add("'$toolName' is not among the server's tools. Available: ${tools.take(MAX_LISTED_TOOLS).joinToString { it.name }}")
                    } else {
                        tool.description?.let { add(it.take(MAX_DESCRIPTION_CHARS)) }
                    }
                },
                latencyMillis = elapsed,
            )
        }.getOrElse { it.toFailedProbe(latency, secrets) }
    }

    override suspend fun submit(request: EngineRequest, onProgress: (EngineProgress) -> Unit): Submission {
        client.connect()
        val tool = resolveTool()
        val arguments = withContext(defaultDispatcher) { buildArguments(request, tool) }
        val result = client.callTool(
            name = toolName,
            arguments = arguments,
            timeoutMillis = config.requestTimeoutSeconds * MILLIS_PER_SECOND,
            onProgress = { progress -> onProgress(EngineProgress(progress.fraction, progress.message)) },
        )
        if (result.isError) {
            throw VisionStudioException.ProviderException(null, result.text.ifBlank { "MCP tool '$toolName' reported an error" })
        }
        val outputs = withContext(defaultDispatcher) { outputsOf(result) }
        if (outputs.isEmpty()) {
            throw VisionStudioException.ProtocolException(null, "MCP tool '$toolName' returned no media")
        }
        return Submission.Completed(outputs, reportedSeed = reportedSeedOf(result))
    }

    override suspend fun poll(job: RemoteJob): JobStatus =
        JobStatus.Failed("MCP tools complete synchronously; nothing to poll for ${job.id}")

    override suspend fun downloadTo(output: EngineOutput, target: File): Long = when (output) {
        is EngineOutput.Inline -> withContext(ioDispatcher) {
            val bytes = output.decode()
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            bytes.size.toLong()
        }
        is EngineOutput.Remote -> {
            val builder = Request.Builder().url(output.url)
            if (config.isSameHost(output.url)) builder.headers(auth)
            http.downloadTo(builder.get().build(), target, config.slot)
        }
    }

    override suspend fun close() = client.close()

    private suspend fun resolveTool(): McpTool? = toolMutex.withLock {
        cachedTool ?: client.listTools().firstOrNull { it.name == toolName }.also { cachedTool = it }
    }

    private fun buildArguments(request: EngineRequest, tool: McpTool?): JsonObject {
        val params = request.parameters
        val dimensions = params.outputDimensions(request.slot)
        val candidates = linkedMapOf<String, JsonElement>()
        fun offer(value: Any?, vararg names: String) {
            val element = when (value) {
                null -> return
                is String -> if (value.isEmpty()) return else JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
            names.forEach { candidates.putIfAbsent(it, element) }
        }

        offer(params.prompt, "prompt")
        offer(params.negativePrompt, "negative_prompt", "negativePrompt")
        offer(dimensions.width, "width")
        offer(dimensions.height, "height")
        offer(params.aspectRatio.label, "aspect_ratio", "aspectRatio")
        offer(params.steps, "steps", "num_inference_steps")
        offer(params.guidanceScale, "guidance_scale", "cfg_scale", "guidance")
        offer(params.seed, "seed")
        // Gradio apps commonly ignore `seed` unless `randomize_seed` is switched off.
        offer(params.seed?.let { false }, "randomize_seed")
        offer(config.modelId, "model", "model_id")
        offer(params.strength, "strength")
        offer(params.durationSeconds, "duration", "duration_seconds", "seconds")
        offer(params.cameraMotion?.takeIf { request.slot == EngineSlot.VIDEO_STUDIO }?.name?.lowercase(), "camera_motion")
        request.sourceImage?.let {
            offer(it.base64(), "image", "image_base64")
            offer(it.dataUri(), "image_url", "image_data_uri")
        }
        request.mask?.let {
            offer(it.base64(), "mask", "mask_base64")
            offer(it.dataUri(), "mask_url", "mask_image_url")
        }

        val declared = (tool?.inputSchema?.get("properties") as? JsonObject)?.keys
        val selected = if (declared.isNullOrEmpty()) {
            candidates.filterKeys { it in CANONICAL_KEYS }
        } else {
            candidates.filterKeys { it in declared }.ifEmpty { candidates.filterKeys { it == "prompt" } }
        }
        return buildJsonObject { selected.forEach { (key, value) -> put(key, value) } }
    }

    /**
     * Media in a tool result. Binary blocks (ImageContent, embedded resource blobs) are the media
     * itself. Text blocks are scanned for URLs or Gradio FileData JSON only when no binary block
     * exists: a Gradio app returning a tuple such as (image, seed, prompt) sends the image as
     * ImageContent plus text blocks `Image URL: <file url>`, `<seed>` and `<prompt>`, and reading
     * that URL as well would save the same image twice.
     */
    private fun outputsOf(result: CallToolResult): List<EngineOutput> {
        val defaultMime = if (config.slot == EngineSlot.VIDEO_STUDIO) "video/mp4" else "image/png"
        val outputs = mutableListOf<EngineOutput>()
        val textOutputs = mutableListOf<EngineOutput>()
        result.content.forEach { block ->
            when (block) {
                is ToolContent.Image -> outputs += EngineOutput.Inline(block.data, block.mimeType)
                is ToolContent.Resource -> {
                    val resource = block.resource
                    val mime = resource.mimeType ?: guessMime(resource.uri, defaultMime)
                    when {
                        resource.blob != null -> outputs += EngineOutput.Inline(resource.blob!!, mime)
                        resource.uri.startsWith("http") -> outputs += EngineOutput.Remote(resource.uri, mime)
                        resource.uri.startsWith("data:") -> parseDataUri(resource.uri)?.let { outputs += it }
                    }
                }
                is ToolContent.Text -> textOutputs += outputsFromText(block.text, defaultMime)
                is ToolContent.Unknown -> Unit
            }
        }
        val media = if (outputs.any { it is EngineOutput.Inline }) outputs else outputs + textOutputs
        return media.distinctBy { (it as? EngineOutput.Remote)?.url ?: it.toString() }
    }

    /**
     * The seed a tool reports using, when it returns one as a bare integer text block (Gradio's
     * (image, seed, prompt) tuple). Null unless exactly one such block exists, so prompts or
     * captions that happen to be numbers are never mistaken for a seed.
     */
    private fun reportedSeedOf(result: CallToolResult): Long? =
        result.content.filterIsInstance<ToolContent.Text>()
            .mapNotNull { it.text.trim().toLongOrNull() }
            .singleOrNull()

    /** Text blocks may carry a JSON envelope or bare URLs. */
    private fun outputsFromText(text: String, defaultMime: String): List<EngineOutput> {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            runCatching { http.json.parseToJsonElement(trimmed) }.getOrNull()
                ?.let { return extractOutputs(it, defaultMime) }
        }
        return URL_PATTERN.findAll(trimmed)
            .map { it.value.trimEnd('.', ',', ')') }
            .map { EngineOutput.Remote(it, guessMime(it, defaultMime)) }
            .toList()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val MAX_LISTED_TOOLS = 6
        const val MAX_DESCRIPTION_CHARS = 240
        val URL_PATTERN = Regex("""https?://[^\s"'<>]+""")
        val CANONICAL_KEYS = setOf(
            "prompt", "negative_prompt", "width", "height", "aspect_ratio", "steps",
            "guidance_scale", "seed", "model", "strength", "duration", "camera_motion", "image", "mask",
        )
    }
}
