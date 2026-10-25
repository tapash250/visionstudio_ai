package com.visionstudio.core.mcp.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object McpMethods {
    const val INITIALIZE: String = "initialize"
    const val INITIALIZED: String = "notifications/initialized"
    const val PING: String = "ping"
    const val TOOLS_LIST: String = "tools/list"
    const val TOOLS_CALL: String = "tools/call"
    const val PROGRESS: String = "notifications/progress"
    const val CANCELLED: String = "notifications/cancelled"
}

/** Revision spoken over the legacy HTTP+SSE transport. */
const val MCP_PROTOCOL_VERSION: String = "2024-11-05"

/** Revision requested over Streamable HTTP. */
const val MCP_LATEST_PROTOCOL_VERSION: String = "2025-06-18"

/** Revisions this client can speak; a server answering `initialize` with another is refused. */
val MCP_SUPPORTED_PROTOCOL_VERSIONS: Set<String> = setOf(MCP_LATEST_PROTOCOL_VERSION, "2025-03-26", MCP_PROTOCOL_VERSION)

@Serializable
data class Implementation(val name: String, val version: String)

@Serializable
internal data class InitializeParams(
    val protocolVersion: String,
    val capabilities: JsonObject,
    val clientInfo: Implementation,
)

@Serializable
data class ServerCapabilities(
    val tools: JsonObject? = null,
    val resources: JsonObject? = null,
    val prompts: JsonObject? = null,
    val logging: JsonObject? = null,
)

@Serializable
data class InitializeResult(
    val protocolVersion: String,
    val capabilities: ServerCapabilities = ServerCapabilities(),
    val serverInfo: Implementation,
    val instructions: String? = null,
)

@Serializable
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject = JsonObject(emptyMap()),
)

@Serializable
internal data class ListToolsParams(val cursor: String? = null)

@Serializable
internal data class ListToolsResult(
    val tools: List<McpTool>,
    val nextCursor: String? = null,
)

@Serializable
internal data class RequestMeta(val progressToken: String)

@Serializable
internal data class CallToolParams(
    val name: String,
    val arguments: JsonObject,
    @SerialName("_meta") val meta: RequestMeta? = null,
)

@Serializable
internal data class CancelledParams(val requestId: Long, val reason: String)

@Serializable
internal data class ProgressParams(
    val progressToken: JsonElement,
    val progress: Double,
    val total: Double? = null,
    val message: String? = null,
)

@Serializable
data class CallToolResult(
    val content: List<ToolContent> = emptyList(),
    val isError: Boolean = false,
) {
    val text: String get() = content.filterIsInstance<ToolContent.Text>().joinToString("\n") { it.text }
}

/** Tool result content blocks. Unrecognised block types decode to [Unknown] rather than failing. */
@Serializable(with = ToolContentSerializer::class)
sealed interface ToolContent {
    @Serializable
    data class Text(val type: String = "text", val text: String) : ToolContent

    @Serializable
    data class Image(val type: String = "image", val data: String, val mimeType: String) : ToolContent

    @Serializable
    data class Resource(val type: String = "resource", val resource: EmbeddedResource) : ToolContent

    @Serializable
    data class Unknown(val type: String) : ToolContent
}

@Serializable
data class EmbeddedResource(
    val uri: String,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null,
)

internal object ToolContentSerializer : JsonContentPolymorphicSerializer<ToolContent>(ToolContent::class) {
    override fun selectDeserializer(element: JsonElement): KSerializer<out ToolContent> =
        when (element.jsonObject["type"]?.jsonPrimitive?.content) {
            "text" -> ToolContent.Text.serializer()
            "image" -> ToolContent.Image.serializer()
            "resource" -> ToolContent.Resource.serializer()
            else -> ToolContent.Unknown.serializer()
        }
}

/** Progress reported by the server for an in-flight request. [fraction] is null if total is unknown. */
data class McpProgress(val progress: Double, val total: Double?, val message: String?) {
    val fraction: Float?
        get() = total?.takeIf { it > 0.0 }?.let { (progress / it).toFloat().coerceIn(0f, 1f) }
}

/** Any server → client notification, surfaced raw for diagnostics and logging. */
data class McpNotification(val method: String, val params: JsonElement?)
