package com.visionstudio.core.mcp.transport

import io.ktor.utils.io.ByteReadChannel

/**
 * Reads `text/event-stream` lines from [channel] with a hard cap of [maxLineBytes] per line.
 *
 * Ktor 2.3's `readUTF8Line(limit)` only enforces its limit once a terminator or the end of the
 * stream arrives; on a live stream that never sends a newline it keeps buffering. This reader
 * counts bytes as they arrive and throws [SseEventTooLargeException] the moment a line is too
 * long, so a misbehaving server can't exhaust memory.
 *
 * Bytes are read in chunks (MCP image results are multi-megabyte base64 lines) and each line is
 * decoded as UTF-8 only once it is complete, so a character split across network reads decodes
 * correctly. Lines end at LF, CRLF or a lone CR, as the SSE specification allows.
 *
 * Not thread-safe: one reader per stream, used from one coroutine.
 */
class SseLineReader(
    private val channel: ByteReadChannel,
    private val maxLineBytes: Int,
    chunkBytes: Int = DEFAULT_CHUNK_BYTES,
) {
    init {
        require(maxLineBytes > 0) { "maxLineBytes must be positive" }
        require(chunkBytes > 0) { "chunkBytes must be positive" }
    }

    private val chunk = ByteArray(chunkBytes)
    private var chunkPos = 0
    private var chunkEnd = 0

    private var line = ByteArray(INITIAL_LINE_BYTES)
    private var lineLength = 0

    /** A CR ended the previous line; a directly following LF belongs to it and is skipped. */
    private var skipLf = false

    /** The next line without its terminator, or null once the stream has ended. */
    suspend fun readLine(): String? {
        lineLength = 0
        while (true) {
            if (chunkPos == chunkEnd && !fill()) {
                // End of stream: hand back a final unterminated line, if any. The event parser
                // discards an event left incomplete, as the specification requires.
                return if (lineLength > 0) decodeLine() else null
            }
            if (skipLf) {
                skipLf = false
                if (chunk[chunkPos] == LF) chunkPos++
                continue
            }
            val start = chunkPos
            var i = start
            while (i < chunkEnd && chunk[i] != LF && chunk[i] != CR) i++
            append(start, i)
            chunkPos = i
            if (i < chunkEnd) {
                skipLf = chunk[i] == CR
                chunkPos = i + 1
                return decodeLine()
            }
        }
    }

    private suspend fun fill(): Boolean {
        val read = channel.readAvailable(chunk, 0, chunk.size)
        if (read <= 0) return false
        chunkPos = 0
        chunkEnd = read
        return true
    }

    private fun append(from: Int, to: Int) {
        val count = to - from
        if (count == 0) return
        if (lineLength.toLong() + count > maxLineBytes) throw SseEventTooLargeException(maxLineBytes)
        if (lineLength + count > line.size) {
            line = line.copyOf(minOf(maxLineBytes, maxOf(line.size * 2, lineLength + count)))
        }
        System.arraycopy(chunk, from, line, lineLength, count)
        lineLength += count
    }

    private fun decodeLine(): String = String(line, 0, lineLength, Charsets.UTF_8).also {
        // Don't keep a multi-megabyte buffer alive after one large event.
        if (line.size > RETAINED_LINE_BYTES) line = ByteArray(INITIAL_LINE_BYTES)
    }

    private companion object {
        const val LF: Byte = '\n'.code.toByte()
        const val CR: Byte = '\r'.code.toByte()
        const val DEFAULT_CHUNK_BYTES = 8 * 1024
        const val INITIAL_LINE_BYTES = 256
        const val RETAINED_LINE_BYTES = 64 * 1024
    }
}
