package com.visionstudio.core.mcp.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal const val JSON_RPC_VERSION = "2.0"

/** Shared codec for every MCP frame. Nulls are omitted, unknown server fields tolerated. */
internal val McpJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
internal data class JsonRpcRequest(
    val jsonrpc: String = JSON_RPC_VERSION,
    val id: Long,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
internal data class JsonRpcNotification(
    val jsonrpc: String = JSON_RPC_VERSION,
    val method: String,
    val params: JsonElement? = null,
)

/** Response frame. [id] stays a raw element: servers may echo it as a number or a string. */
@Serializable
internal data class JsonRpcResponse(
    val jsonrpc: String = JSON_RPC_VERSION,
    val id: JsonElement,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
) {
    companion object {
        const val PARSE_ERROR: Int = -32700
        const val INVALID_REQUEST: Int = -32600
        const val METHOD_NOT_FOUND: Int = -32601
        const val INVALID_PARAMS: Int = -32602
        const val INTERNAL_ERROR: Int = -32603
    }
}
