package com.visionstudio.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTest {

    @Test
    fun `closestTo picks the preset matching common photo shapes`() {
        assertEquals(AspectRatio.SQUARE, AspectRatio.closestTo(1000, 1000))
        assertEquals(AspectRatio.LANDSCAPE_4_3, AspectRatio.closestTo(4032, 3024))
        assertEquals(AspectRatio.PORTRAIT_3_4, AspectRatio.closestTo(3024, 4032))
        assertEquals(AspectRatio.LANDSCAPE_16_9, AspectRatio.closestTo(1920, 1080))
        assertEquals(AspectRatio.PORTRAIT_9_16, AspectRatio.closestTo(1080, 1920))
        assertEquals(AspectRatio.CINEMATIC_21_9, AspectRatio.closestTo(3440, 1440))
    }

    @Test
    fun `closestTo is symmetric on a log scale`() {
        // 2:1 sits between 16:9 and 21:9; its mirror must land on the mirrored preset.
        assertEquals(AspectRatio.LANDSCAPE_16_9, AspectRatio.closestTo(2000, 1000))
        assertEquals(AspectRatio.PORTRAIT_9_16, AspectRatio.closestTo(1000, 2000))
    }

    @Test
    fun `short label reflects protocol`() {
        val rest = EngineConfig(EngineSlot.TEXT_TO_IMAGE, ProviderProtocol.REST, "https://a.dev", "fal-ai/flux/dev")
        val mcp = EngineConfig(EngineSlot.TEXT_TO_IMAGE, ProviderProtocol.MCP, "https://a.dev/sse", "", mcpToolName = "make_image")
        assertEquals("dev", rest.shortLabel)
        assertEquals("MCP · make_image", mcp.shortLabel)
        assertEquals("", EngineConfig.unconfigured(EngineSlot.IMAGE_EDIT).shortLabel)
    }

    @Test
    fun `mcp engines need a tool but not a model, rest engines need a model`() {
        val mcp = EngineConfig(EngineSlot.IMAGE_EDIT, ProviderProtocol.MCP, "https://a.dev/sse", "", mcpToolName = "inpaint")
        val mcpNoTool = mcp.copy(mcpToolName = null)
        val rest = EngineConfig(EngineSlot.IMAGE_EDIT, ProviderProtocol.REST, "https://a.dev", "")
        assertEquals(true, mcp.isConfigured)
        assertEquals(false, mcpNoTool.isConfigured)
        assertEquals(false, rest.isConfigured)
        assertEquals(true, rest.copy(modelId = "m").isConfigured)
    }
}
