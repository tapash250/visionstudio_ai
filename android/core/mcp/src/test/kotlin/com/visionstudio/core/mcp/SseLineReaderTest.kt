@file:Suppress("DEPRECATION") // Ktor 2.3 ByteChannel; replaced by kotlinx-io only in Ktor 3.

package com.visionstudio.core.mcp.transport

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseLineReaderTest {

    private fun lines(text: String, maxLineBytes: Int = 1_024, chunkBytes: Int = 8): List<String> = runBlocking {
        val reader = SseLineReader(ByteReadChannel(text.toByteArray()), maxLineBytes, chunkBytes)
        buildList { while (true) add(reader.readLine() ?: break) }
    }

    @Test
    fun `lf, crlf and lone cr all end a line`() {
        assertEquals(listOf("a", "b", "c", "", "d"), lines("a\nb\r\nc\r\rd\n"))
    }

    @Test
    fun `a crlf split across two reads is one terminator`() {
        // chunkBytes = 3 puts the CR and the LF of "ab\r\n" in different reads.
        assertEquals(listOf("ab", "cd"), lines("ab\r\ncd\n", chunkBytes = 3))
    }

    @Test
    fun `a final unterminated line is returned once, then end of stream`() {
        assertEquals(listOf("data: x", "tail"), lines("data: x\ntail"))
    }

    @Test
    fun `multi-byte characters split across reads decode intact`() = runBlocking<Unit> {
        val text = "x".repeat(5) + "\u30F3\uD83D\uDE00" + "end"
        val bytes = (text + "\n").toByteArray()
        for (cut in 1 until bytes.size) {
            val channel = ByteChannel(autoFlush = true)
            launch {
                channel.writeFully(bytes, 0, cut)
                yield()
                channel.writeFully(bytes, cut, bytes.size - cut)
                channel.close()
            }
            val reader = SseLineReader(channel, maxLineBytes = 64, chunkBytes = 4)
            assertEquals("split at byte $cut", text, reader.readLine())
            assertNull(reader.readLine())
        }
    }

    @Test
    fun `a line exactly at the limit is accepted`() {
        assertEquals(listOf("A".repeat(64)), lines("A".repeat(64) + "\n", maxLineBytes = 64))
    }

    @Test(expected = SseEventTooLargeException::class)
    fun `a line one byte over the limit is refused`() {
        lines("A".repeat(65) + "\n", maxLineBytes = 64)
    }

    @Test
    fun `the limit trips on a live stream that never sends a newline`() = runBlocking<Unit> {
        val channel = ByteChannel(autoFlush = true)
        val reader = SseLineReader(channel, maxLineBytes = 1_024)
        val read = async { runCatching { reader.readLine() } }

        // The stream stays open: no terminator and no end of stream ever arrives.
        channel.writeFully(ByteArray(4_096) { 'A'.code.toByte() })

        val failure = withTimeout(2_000) { read.await() }.exceptionOrNull()
        assertTrue("expected SseEventTooLargeException, got $failure", failure is SseEventTooLargeException)
        channel.cancel(null)
    }

    @Test
    fun `a large line under the limit survives many small reads`() {
        val big = "Q".repeat(100_000)
        assertEquals(listOf(big, "next"), lines("$big\nnext\n", maxLineBytes = 200_000, chunkBytes = 7))
    }
}
