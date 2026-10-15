@file:Suppress("DEPRECATION") // Ktor 2.3 ByteChannel; replaced by kotlinx-io only in Ktor 3.

package com.visionstudio.core.mcp

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections

/**
 * Scripted MCP server over [MockEngine]. The GET returns a live SSE stream backed by a
 * [ByteChannel]; each POSTed JSON-RPC message is recorded and answered by [onRequest], whose
 * frames are written back onto the stream the way a real HTTP+SSE server does.
 */
class FakeMcpServer(
    private val endpointPath: String = "/messages?sessionId=abc123",
    private val sseStatus: HttpStatusCode = HttpStatusCode.OK,
    private val sseContentType: ContentType = ContentType.Text.EventStream,
    private val announceEndpoint: Boolean = true,
) {
    val stream = ByteChannel(autoFlush = true)
    val received: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    val receivedHeaders: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())

    /** Returns raw SSE frames (already formatted) to push after receiving a request. */
    var onRequest: suspend FakeMcpServer.(JsonObject) -> List<String> = { message -> defaultHandler(message) }

    val tools: MutableList<String> = mutableListOf(
        """{"name":"generate_image","description":"Text to image","inputSchema":{"type":"object","properties":{"prompt":{"type":"string"}}}}""",
    )

    val engine = MockEngine { request -> handle(request) }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        receivedHeaders += request.headers.entries().associate { it.key to it.value.joinToString(",") }
        return when (request.method) {
            HttpMethod.Get -> when {
                sseStatus != HttpStatusCode.OK -> respond("""{"error":"denied"}""", sseStatus)
                else -> {
                    if (announceEndpoint) push(sseFrame("endpoint", endpointPath))
                    respond(stream, sseStatus, headersOf(HttpHeaders.ContentType, sseContentType.toString()))
                }
            }
            HttpMethod.Post -> {
                val body = (request.body as TextContent).text
                val message = Json.parseToJsonElement(body).jsonObject
                received += message
                onRequest(message).forEach { push(it) }
                respond("Accepted", HttpStatusCode.Accepted)
            }
            else -> respond("", HttpStatusCode.MethodNotAllowed)
        }
    }

    suspend fun push(frame: String) {
        stream.writeStringUtf8(frame)
    }

    fun closeStream() {
        stream.close()
    }

    fun requestsFor(method: String): List<JsonObject> =
        synchronized(received) { received.filter { it["method"]?.jsonPrimitive?.content == method } }

    fun defaultHandler(message: JsonObject): List<String> {
        val id = message["id"] ?: return emptyList()
        return when (message["method"]?.jsonPrimitive?.content) {
            "initialize" -> listOf(result(id, INITIALIZE_RESULT))
            "tools/list" -> listOf(result(id, """{"tools":[${tools.joinToString(",")}]}"""))
            "ping" -> listOf(result(id, "{}"))
            else -> listOf(error(id, -32601, "Method not found"))
        }
    }

    companion object {
        const val INITIALIZE_RESULT =
            """{"protocolVersion":"2024-11-05","capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"fake-diffusion","version":"9.9"}}"""

        fun sseFrame(event: String?, data: String): String = buildString {
            if (event != null) append("event: ").append(event).append('\n')
            data.lines().forEach { append("data: ").append(it).append('\n') }
            append('\n')
        }

        fun result(id: JsonElement, resultJson: String): String =
            sseFrame("message", """{"jsonrpc":"2.0","id":$id,"result":$resultJson}""")

        fun error(id: JsonElement, code: Int, message: String): String =
            sseFrame("message", """{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"$message"}}""")
    }
}
