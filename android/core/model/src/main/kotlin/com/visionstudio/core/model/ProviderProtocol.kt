package com.visionstudio.core.model

import kotlinx.serialization.Serializable

/** Transport used to reach a generation engine. */
@Serializable
enum class ProviderProtocol(val displayName: String) {
    /** Conventional HTTP/JSON provider APIs (OpenAI-compatible, Fal.ai queue, custom). */
    REST("Standard REST"),

    /** Model Context Protocol: JSON-RPC 2.0 over Streamable HTTP, or legacy HTTP+SSE. */
    MCP("Model Context Protocol"),
}

/** Request/response dialect spoken by a [ProviderProtocol.REST] engine. */
@Serializable
enum class RestDialect(val displayName: String) {
    OPENAI_COMPATIBLE("OpenAI-compatible"),
    FAL_QUEUE("Fal.ai Queue"),
    CUSTOM("Custom JSON"),
}

/** How a credential is attached to outbound REST requests. */
@Serializable
enum class AuthScheme {
    /** `Authorization: Bearer <key>` */
    BEARER,

    /** `Authorization: Key <key>` (Fal.ai style). */
    KEY,

    /** Raw key placed in [EngineConfig.customAuthHeader]. */
    CUSTOM_HEADER,

    NONE,
}
