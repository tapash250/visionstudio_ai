package com.visionstudio.core.common.export

import com.visionstudio.core.model.MediaItem
import com.visionstudio.core.model.MediaType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Identifies files written by this app inside their own metadata. */
const val EXPORT_APP_NAME: String = "VisionStudio AI"

/**
 * The generation parameters as one compact JSON object, ready for EXIF `UserComment`.
 *
 * JSON rather than free text so another tool (or a future "import settings" feature) can read it
 * back reliably. The result is strictly 7-bit ASCII: every character above U+007F is written as a
 * `\uXXXX` escape. That is still valid JSON, and it means the bytes mean the same thing under the
 * `ASCII` character-set marker EXIF requires, for a prompt in any language.
 *
 * Only values the item really has are included, and the whole thing is capped at
 * [MAX_COMMENT_BYTES] (a JPEG's EXIF block must fit in 64 KB): if it is too large, the negative
 * prompt is dropped first, then the prompt is shortened.
 */
fun MediaItem.generationComment(): String {
    var comment = buildComment(includeNegative = true, promptLimit = Int.MAX_VALUE)
    if (comment.length > MAX_COMMENT_BYTES) comment = buildComment(includeNegative = false, promptLimit = Int.MAX_VALUE)
    if (comment.length > MAX_COMMENT_BYTES) comment = buildComment(includeNegative = false, promptLimit = SHORT_PROMPT_CHARS)
    return comment
}

private fun MediaItem.buildComment(includeNegative: Boolean, promptLimit: Int): String {
    val p = parameters
    val json: JsonObject = buildJsonObject {
        put("app", EXPORT_APP_NAME)
        put("type", if (type == MediaType.VIDEO) "video" else "image")
        put("prompt", p.prompt.take(promptLimit))
        if (includeNegative && p.negativePrompt.isNotBlank()) put("negative_prompt", p.negativePrompt)
        if (p.modelId.isNotBlank()) put("model", p.modelId)
        p.seed?.let { put("seed", it) }
        put("width", width)
        put("height", height)
        put("aspect_ratio", p.aspectRatio.label)
        if (type == MediaType.IMAGE) {
            put("steps", p.steps)
            put("guidance_scale", p.guidanceScale)
        }
        p.strength?.let { put("strength", it) }
        put("created_at", Instant.ofEpochMilli(createdAtEpochMillis).toString())
    }
    return asciiOnly(json.toString())
}

/** Rewrites every non-ASCII character as a JSON `\uXXXX` escape. UTF-16 surrogate pairs escape naturally, one unit at a time. */
internal fun asciiOnly(json: String): String {
    if (json.all { it.code < ASCII_LIMIT }) return json
    return buildString(json.length + ESCAPE_PADDING) {
        for (ch in json) {
            if (ch.code < ASCII_LIMIT) append(ch) else append("\\u").append(String.format(Locale.ROOT, "%04x", ch.code))
        }
    }
}

/**
 * A readable, collision-resistant file name: `VisionStudio_20261012_153000_421337.png`.
 * The time is the item's creation time in [zone]; the suffix is the seed when known, otherwise
 * the start of the item id. Only `[A-Za-z0-9_.-]` can appear, so it is safe on every file system.
 */
fun MediaItem.exportFileName(zone: ZoneId): String {
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
        .format(Instant.ofEpochMilli(createdAtEpochMillis).atZone(zone))
    val suffix = parameters.seed?.toString() ?: id.take(ID_SUFFIX_CHARS)
    return sanitize("${EXPORT_APP_NAME.replace(" ", "")}_${stamp}_$suffix") + "." + extensionFor(mimeType, type)
}

internal fun extensionFor(mimeType: String, type: MediaType): String = when (mimeType.lowercase(Locale.ROOT)) {
    "image/jpeg", "image/jpg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "video/mp4" -> "mp4"
    "video/webm" -> "webm"
    "video/quicktime" -> "mov"
    else -> type.fileExtension
}

/**
 * Keeps `[A-Za-z0-9_.-]`, replaces everything else with `_`, collapses runs of dots (so `..` can
 * never appear) and never begins with a dot (so the result is never a hidden file).
 */
internal fun sanitize(name: String): String =
    name.map { if (it.isLetterOrDigit() && it.code < ASCII_LIMIT || it in "_.-") it else '_' }
        .joinToString("")
        .replace(Regex("\\.{2,}"), ".")
        .trimStart('.')
        .ifEmpty { "_" }

/** `yyyy:MM:dd HH:mm:ss`, the fixed format EXIF date tags require, in [zone]. */
internal fun exifDateTime(epochMillis: Long, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT).format(Instant.ofEpochMilli(epochMillis).atZone(zone))

private const val MAX_COMMENT_BYTES = 48_000
private const val SHORT_PROMPT_CHARS = 1_000
private const val ASCII_LIMIT = 0x80
private const val ESCAPE_PADDING = 64
private const val ID_SUFFIX_CHARS = 8
