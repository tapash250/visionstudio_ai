package com.visionstudio.core.mcp

import com.visionstudio.core.mcp.transport.SseEvent
import com.visionstudio.core.mcp.transport.SseEventParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseEventParserTest {

    private fun parse(vararg lines: String): List<SseEvent> {
        val parser = SseEventParser()
        return lines.mapNotNull(parser::feedLine)
    }

    @Test
    fun `named event with single data line`() {
        val events = parse("event: endpoint", "data: /messages?sessionId=1", "")
        assertEquals(listOf(SseEvent("endpoint", "/messages?sessionId=1", null, null)), events)
    }

    @Test
    fun `type defaults to message and multi-line data joins with newline`() {
        val events = parse("data: {\"a\":", "data: 1}", "")
        assertEquals("message", events.single().event)
        assertEquals("{\"a\":\n1}", events.single().data)
    }

    @Test
    fun `comments and keep-alive pings are ignored`() {
        assertEquals(emptyList<SseEvent>(), parse(": ping", "", ":keepalive", ""))
    }

    @Test
    fun `only one leading space is stripped and colonless field has empty value`() {
        val events = parse("data:  two-spaces", "data", "")
        assertEquals(" two-spaces\n", events.single().data)
    }

    @Test
    fun `CRLF line endings are tolerated`() {
        val events = parse("event: message\r", "data: x\r", "\r")
        assertEquals(SseEvent("message", "x", null, null), events.single())
    }

    @Test
    fun `id persists across events and retry is parsed`() {
        val events = parse("id: 7", "retry: 3000", "data: a", "", "data: b", "")
        assertEquals("7", events[0].id)
        assertEquals(3000L, events[0].retryMillis)
        assertEquals("7", events[1].id)
    }

    @Test
    fun `event without data is not dispatched and does not leak its type`() {
        val events = parse("event: endpoint", "", "data: hello", "")
        assertEquals("message", events.single().event)
    }

    @Test
    fun `incomplete trailing event is never emitted`() {
        val parser = SseEventParser()
        assertNull(parser.feedLine("data: partial"))
    }
}
