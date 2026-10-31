package com.visionstudio.core.network

import com.visionstudio.core.mcp.McpClientFactory
import com.visionstudio.core.mcp.McpServerConfig
import com.visionstudio.core.model.AuthScheme
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.core.network.engine.authHeaders
import com.visionstudio.core.network.engine.mcp.McpGenerationEngine
import com.visionstudio.core.network.http.RestHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Connection check against the real FLUX 3 Image Klein Space. Opt-in, since it needs the network
 * and depends on a third-party server: run with `VISIONSTUDIO_LIVE_MCP=1`. Optionally set
 * `VISIONSTUDIO_LIVE_MCP_URL` to check another Space and `HF_TOKEN` to try a real token.
 */
class McpLiveSpaceTest {

    private val url = System.getenv("VISIONSTUDIO_LIVE_MCP_URL") ?: "https://black-forest-labs-flux-3-image-klein.hf.space/gradio_api/mcp/"

    @Before fun requireOptIn() = assumeTrue("set VISIONSTUDIO_LIVE_MCP=1 to run", System.getenv("VISIONSTUDIO_LIVE_MCP") == "1")

    @Test
    fun `the live Space connects and lists the infer tool with no auth`() = check(AuthScheme.NONE, null)

    @Test
    fun `the live Space connects with bearer auth`() =
        check(AuthScheme.BEARER, System.getenv("HF_TOKEN") ?: "hf_placeholderNotARealToken0000")

    private fun check(auth: AuthScheme, apiKey: String?) = runBlocking {
        val config = EngineConfig(
            slot = EngineSlot.TEXT_TO_IMAGE,
            protocol = ProviderProtocol.MCP,
            endpointUrl = url,
            modelId = "black-forest-labs/flux-3-image-klein",
            authScheme = auth,
            mcpToolName = "flux_3_image_klein_infer",
            requestTimeoutSeconds = 60,
        )
        val okHttp = OkHttpClient()
        val client = McpClientFactory(okHttp, Dispatchers.IO, Dispatchers.Default).create(
            McpServerConfig(url = url, headers = config.authHeaders(apiKey), requestTimeoutMillis = 60_000L),
        )
        val engine = McpGenerationEngine(config, apiKey, client, RestHttpClient(okHttp, Dispatchers.IO, Dispatchers.Default), Dispatchers.IO, Dispatchers.Default)
        val probe = try {
            engine.testConnection()
        } finally {
            engine.close()
        }
        println("live MCP [$auth]: ${probe.headline} · ${probe.details} · ${probe.latencyMillis} ms")
        assertTrue("${probe.headline}: ${probe.details}", probe.success)
    }
}
