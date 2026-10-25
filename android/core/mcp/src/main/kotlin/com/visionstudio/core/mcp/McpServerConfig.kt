package com.visionstudio.core.mcp

import com.visionstudio.core.mcp.transport.SseEventParser

/** How [McpClient] talks to a server. */
enum class McpTransport(val displayName: String) {
    /**
     * Streamable HTTP first; if the server answers the `initialize` POST with an HTTP 4xx other
     * than 401/403, fall back to the legacy HTTP+SSE transport at the same URL. This is the
     * backwards-compatibility procedure described by the MCP 2025-03-26 specification.
     */
    AUTO("Automatic"),

    /** MCP 2025-03-26 / 2025-06-18: every JSON-RPC message is a POST to one endpoint. */
    STREAMABLE_HTTP("Streamable HTTP"),

    /** MCP 2024-11-05: a long-lived SSE GET announces where to POST messages. */
    SSE("Legacy SSE"),
}

/**
 * Connection parameters for an MCP server.
 *
 * @property url the MCP endpoint: the Streamable HTTP URL (for example a Gradio Space's
 *   `/gradio_api/mcp/`), or the legacy SSE stream URL, which announces the POST endpoint.
 * @property headers extra headers (typically `Authorization`) sent on every request.
 * @property requestTimeoutMillis default deadline for one request: posting it and receiving its
 *   response together.
 * @property maxEventBytes largest server-sent event accepted: no single line may exceed this many
 *   bytes, nor one event's joined data this many characters. A larger one fails the session with
 *   a protocol error instead of exhausting memory.
 */
data class McpServerConfig(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val transport: McpTransport = McpTransport.AUTO,
    val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    val maxEventBytes: Int = DEFAULT_MAX_EVENT_BYTES,
) {
    init {
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "url must be an http(s) URL"
        }
        require(connectTimeoutMillis > 0 && requestTimeoutMillis > 0) { "Timeouts must be positive" }
        require(maxEventBytes > 0) { "maxEventBytes must be positive" }
    }

    /** Redacted so credentials never reach logs or crash reports. */
    override fun toString(): String =
        "McpServerConfig(url=$url, headers=${headers.keys}, transport=$transport, connect=$connectTimeoutMillis, request=$requestTimeoutMillis)"

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Long = 15_000
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS: Long = 120_000
        const val DEFAULT_MAX_EVENT_BYTES: Int = SseEventParser.DEFAULT_MAX_DATA_CHARS
    }
}
