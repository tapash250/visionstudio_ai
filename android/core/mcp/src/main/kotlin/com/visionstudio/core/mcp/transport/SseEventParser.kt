package com.visionstudio.core.mcp.transport

/** A dispatched Server-Sent Event. */
data class SseEvent(
    val event: String,
    val data: String,
    val id: String?,
    val retryMillis: Long?,
)

/**
 * Incremental parser implementing the WHATWG `text/event-stream` interpretation rules:
 * `field: value` lines (one optional leading space stripped), `:` comments ignored, multi-line
 * `data` joined with `\n`, an event dispatched on a blank line, and type defaulting to `message`.
 * An event left incomplete at end of stream is discarded, as the spec requires.
 */
class SseEventParser {
    private val data = StringBuilder()
    private var hasData = false
    private var eventType: String? = null
    private var lastEventId: String? = null
    private var retry: Long? = null

    /** Feeds one line (without its terminator). Returns an event when [line] completes one. */
    fun feedLine(line: String): SseEvent? {
        val clean = line.removeSuffix("\r")
        if (clean.isEmpty()) return dispatch()
        if (clean.startsWith(':')) return null

        val colon = clean.indexOf(':')
        val field = if (colon == -1) clean else clean.substring(0, colon)
        val value = when {
            colon == -1 -> ""
            colon + 1 < clean.length && clean[colon + 1] == ' ' -> clean.substring(colon + 2)
            else -> clean.substring(colon + 1)
        }

        when (field) {
            "event" -> eventType = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "id" -> if ('\u0000' !in value) lastEventId = value
            "retry" -> value.toLongOrNull()?.let { retry = it }
        }
        return null
    }

    private fun dispatch(): SseEvent? {
        if (!hasData) {
            eventType = null
            return null
        }
        val event = SseEvent(
            event = eventType?.takeIf { it.isNotEmpty() } ?: DEFAULT_EVENT,
            data = data.toString(),
            id = lastEventId,
            retryMillis = retry,
        )
        data.setLength(0)
        hasData = false
        eventType = null
        return event
    }

    companion object {
        const val DEFAULT_EVENT: String = "message"
    }
}
