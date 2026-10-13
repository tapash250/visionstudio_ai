package com.visionstudio.core.network.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Base64

/**
 * Decodes a preview carried as a data URI or bare base64 string. The MIME type is sniffed
 * from magic bytes when the provider does not declare one. Returns null for anything that is
 * not a recognisable image, so a malformed preview never aborts a generation.
 */
internal fun decodePreviewPayload(raw: String): BinaryPayload? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val (base64, declaredMime) = if (trimmed.startsWith("data:")) {
        val inline = parseDataUri(trimmed) ?: return null
        inline.base64 to inline.mimeType
    } else {
        trimmed to null
    }
    val bytes = runCatching { Base64.getMimeDecoder().decode(base64) }.getOrNull() ?: return null
    val mime = declaredMime ?: sniffImageMime(bytes) ?: return null
    return BinaryPayload(bytes, mime)
}

internal fun sniffImageMime(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "image/png"
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
    bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
    else -> null
}

/**
 * Reads progress from the field names common to streaming backends: `progress` as a 0..1
 * fraction or 0..100 percentage, `percent`, or `step` / `total_steps`.
 */
internal fun JsonObject.streamFraction(): Float? {
    fun number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    val raw = number("progress") ?: number("percent")?.let { it / PERCENT }
    if (raw != null) return (if (raw > 1.0) raw / PERCENT else raw).toFloat().coerceIn(0f, 1f)
    val step = number("step") ?: return null
    val total = number("total_steps") ?: number("steps") ?: return null
    return if (total > 0) (step / total).toFloat().coerceIn(0f, 1f) else null
}

internal fun JsonObject.previewString(): String? =
    PREVIEW_KEYS.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull }

internal fun JsonObject.stringField(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private val PREVIEW_KEYS = listOf("preview", "preview_image", "latent_preview", "partial_image")
private const val PERCENT = 100.0
