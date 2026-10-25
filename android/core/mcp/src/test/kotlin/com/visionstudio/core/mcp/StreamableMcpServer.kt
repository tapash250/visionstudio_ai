package com.visionstudio.core.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.Collections

/**
 * Scripted MCP server speaking Streamable HTTP over a real socket ([MockWebServer]), shaped like
 * a Gradio Space's `/gradio_api/mcp/`: every JSON-RPC message is a POST; requests are answered
 * as `text/event-stream` (or `application/json` when [respondWithJson]) and notifications with
 * 202. Every request is recorded, headers included.
 */
class StreamableMcpServer(
    var respondWithJson: Boolean = false,
    var sessionId: String? = "session-1",
    var protocolVersion: String = "2025-06-18",
) {
    val server = MockWebServer()
    val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())

    /** Overrides the answer to a method; return null to fall back to the default handler. */
    var override: (Recorded) -> MockResponse? = { null }

    var tools: List<String> = listOf(
        """{"name":"flux_3_image_klein__load_initial","inputSchema":{"type":"object","properties":{}}}""",
        """{"name":"flux_3_image_klein_infer","description":"Generate or edit images with FLUX 3 Image Klein.","inputSchema":{"type":"object","required":["prompt"],"properties":{"prompt":{"type":"string"},"seed":{"type":"number"},"randomize_seed":{"type":"boolean","default":true},"width":{"type":"number"},"height":{"type":"number"},"num_inference_steps":{"type":"number"},"guidance_scale":{"type":"number"}}}}""",
    )

    /** Content blocks for tools/call, as JSON. Default: Gradio's (image, seed, prompt) tuple. */
    var toolResultContent: String = gradioTupleContent(base64Png = TINY_PNG_BASE64, seed = 7, prompt = "a glass koi, studio lighting")

    data class Recorded(val request: RecordedRequest, val message: JsonObject?) {
        val method: String? get() = message?.get("method")?.jsonPrimitive?.content
        val id: JsonElement? get() = message?.get("id")
        fun header(name: String): String? = request.getHeader(name)
    }

    fun start(): StreamableMcpServer = apply {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val message = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                val recorded = Recorded(request, message)
                requests += recorded
                return override(recorded) ?: defaultResponse(recorded)
            }
        }
        server.start()
    }

    fun url(path: String = "/gradio_api/mcp/"): String = server.url(path).toString()

    fun shutdown() = server.shutdown()

    fun requestsFor(method: String): List<Recorded> = synchronized(requests) { requests.filter { it.method == method } }

    private fun defaultResponse(recorded: Recorded): MockResponse {
        if (recorded.request.method == "DELETE") return MockResponse().setResponseCode(200)
        if (recorded.request.method != "POST") return MockResponse().setResponseCode(405)
        val id = recorded.id ?: return MockResponse().setResponseCode(202).setHeader("Content-Type", "application/json")
        val result = when (recorded.method) {
            "initialize" -> """{"protocolVersion":"$protocolVersion","capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"FLUX 3 Image Klein","version":"1.30.0"}}"""
            "tools/list" -> """{"tools":[${tools.joinToString(",")}]}"""
            "tools/call" -> """{"content":$toolResultContent,"isError":false}"""
            "ping" -> "{}"
            else -> return reply(error(id, -32601, "Method not found"), initialize = false)
        }
        val progress = if (recorded.method == "tools/call") progressFrames(recorded) else emptyList()
        return reply(listOf(rpcResult(id, result)), initialize = recorded.method == "initialize", progress = progress)
    }

    private fun progressFrames(recorded: Recorded): List<String> {
        val token = recorded.message?.get("params")?.jsonObject?.get("_meta")?.jsonObject?.get("progressToken") ?: return emptyList()
        return listOf(
            """{"method":"notifications/progress","params":{"progressToken":$token,"progress":0.0,"message":"In queue. Position 0 out of 1."},"jsonrpc":"2.0"}""",
            """{"method":"notifications/progress","params":{"progressToken":$token,"progress":1.0,"message":"Processing"},"jsonrpc":"2.0"}""",
            """{"method":"notifications/progress","params":{"progressToken":$token,"progress":2.0},"jsonrpc":"2.0"}""",
        )
    }

    private fun reply(messages: List<String>, initialize: Boolean, progress: List<String> = emptyList()): MockResponse {
        val response = MockResponse().setResponseCode(200)
        if (initialize) sessionId?.let { response.setHeader("Mcp-Session-Id", it) }
        return if (respondWithJson) {
            response.setHeader("Content-Type", "application/json").setBody(messages.single())
        } else {
            // Gradio's server uses CRLF line endings in its event stream.
            response.setHeader("Content-Type", "text/event-stream")
                .setBody((progress + messages).joinToString("") { "event: message\r\ndata: $it\r\n\r\n" })
        }
    }

    companion object {
        /** A 1×1 transparent PNG. */
        const val TINY_PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

        fun rpcResult(id: JsonElement, resultJson: String) = """{"jsonrpc":"2.0","id":$id,"result":$resultJson}"""

        fun error(id: JsonElement, code: Int, message: String) =
            listOf("""{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"$message"}}""")

        /** The exact block layout the live Space returns for `flux_3_image_klein_infer`. */
        fun gradioTupleContent(base64Png: String, seed: Long, prompt: String, fileUrl: String = "https://example.hf.space/gradio_api/file=/tmp/gradio/abc/image.png") =
            """[{"type":"image","data":"$base64Png","mimeType":"image/png"},""" +
                """{"type":"text","text":"Image URL: $fileUrl"},""" +
                """{"type":"text","text":"$seed"},""" +
                """{"type":"text","text":"$prompt"}]"""
    }
}
