package com.visionstudio.core.mcp

import com.visionstudio.core.mcp.transport.SseEventParser

/**
 * Connection parameters for an MCP server using the HTTP+SSE transport (spec 2024-11-05).
 *
 * @property sseUrl the event-stream URL; the server announces the POST endpoint on it.
 * @property headers extra headers (typically `Authorization`) sent on both the stream and POSTs.
 * @property requestTimeoutMillis default deadline for one request: posting it and receiving its
 *   response together.
 * @property maxEventBytes largest server-sent event accepted: no single line may exceed this many
 *   bytes, nor one event's joined data this many characters. A larger one fails the session with
 *   a protocol error instead of exhausting memory.
 */
data class McpServerConfig(
    val sseUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    val maxEventBytes: Int = DEFAULT_MAX_EVENT_BYTES,
) {
    init {
        require(sseUrl.startsWith("https://") || sseUrl.startsWith("http://")) {
            "sseUrl must be an http(s) URL"
        }
        require(connectTimeoutMillis > 0 && requestTimeoutMillis > 0) { "Timeouts must be positive" }
        require(maxEventBytes > 0) { "maxEventBytes must be positive" }
    }

    /** Redacted so credentials never reach logs or crash reports. */
    override fun toString(): String =
        "McpServerConfig(sseUrl=$sseUrl, headers=${headers.keys}, connect=$connectTimeoutMillis, request=$requestTimeoutMillis)"

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Long = 15_000
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS: Long = 120_000
        const val DEFAULT_MAX_EVENT_BYTES: Int = SseEventParser.DEFAULT_MAX_DATA_CHARS
    }
}
