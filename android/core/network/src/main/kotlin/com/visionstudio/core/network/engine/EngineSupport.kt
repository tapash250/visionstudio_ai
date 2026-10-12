package com.visionstudio.core.network.engine

import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.model.AuthScheme
import com.visionstudio.core.model.CameraMotion
import com.visionstudio.core.model.Dimensions
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import kotlin.coroutines.cancellation.CancellationException

/** Header name/value pairs for [EngineConfig.authScheme]. Empty when no key is configured. */
fun EngineConfig.authHeaders(apiKey: String?): Map<String, String> {
    val key = apiKey?.trim().orEmpty()
    if (key.isEmpty()) return emptyMap()
    return when (authScheme) {
        AuthScheme.BEARER -> mapOf("Authorization" to "Bearer $key")
        AuthScheme.KEY -> mapOf("Authorization" to "Key $key")
        AuthScheme.CUSTOM_HEADER -> mapOf(requireNotNull(customAuthHeader) to key)
        AuthScheme.NONE -> emptyMap()
    }
}

internal fun Request.Builder.headers(values: Map<String, String>): Request.Builder = apply {
    values.forEach { (name, value) -> header(name, value) }
}

/** Joins a base URL and path without doubling or dropping slashes. */
internal fun joinUrl(base: String, path: String): String =
    base.trimEnd('/') + "/" + path.trimStart('/')

/** True when [url] points at the same host as the configured endpoint (credentials may be sent). */
internal fun EngineConfig.isSameHost(url: String): Boolean {
    val endpointHost = endpointUrl.toHttpUrlOrNull()?.host ?: return false
    return url.toHttpUrlOrNull()?.host.equals(endpointHost, ignoreCase = true)
}

/** Image outputs use a 1024 px long edge on a 64 px grid; video uses 1280 px on a 16 px grid (720p class). */
internal fun GenerationParameters.outputDimensions(slot: EngineSlot): Dimensions =
    if (slot == EngineSlot.VIDEO_STUDIO) {
        aspectRatio.dimensionsFor(longEdge = VIDEO_LONG_EDGE, alignment = VIDEO_ALIGNMENT)
    } else {
        aspectRatio.dimensionsFor()
    }

/** Folds camera motion into the prompt for providers without a dedicated field. */
internal fun GenerationParameters.promptWithDirectives(): String {
    val motion = cameraMotion?.takeIf { it != CameraMotion.STATIC } ?: return prompt
    return "$prompt. Camera movement: ${motion.displayName.lowercase()}."
}

internal fun requireSourceImage(request: EngineRequest): BinaryPayload =
    request.sourceImage ?: throw VisionStudioException.ValidationException(
        "sourceImage",
        "${request.slot.displayName} requires a source image",
    )

/**
 * Walks a provider JSON response and collects media outputs from the shapes used by OpenAI
 * (`data[].b64_json|url`), Fal.ai (`images[].url`, `video.url`), Replicate-style `output`
 * arrays, and data URIs. Depth- and count-bounded.
 */
internal fun extractOutputs(element: JsonElement, defaultMime: String): List<EngineOutput> {
    val outputs = LinkedHashMap<String, EngineOutput>()

    fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    fun addUrl(url: String, mime: String?) {
        if (outputs.size >= MAX_OUTPUTS) return
        when {
            url.startsWith("data:") -> parseDataUri(url)?.let { outputs[url.take(DEDUPE_KEY_LENGTH)] = it }
            url.startsWith("http://") || url.startsWith("https://") ->
                outputs.getOrPut(url) { EngineOutput.Remote(url, mime ?: guessMime(url, defaultMime)) }
        }
    }

    fun visit(node: JsonElement, depth: Int) {
        if (depth > MAX_DEPTH || outputs.size >= MAX_OUTPUTS) return
        when (node) {
            is JsonObject -> {
                val mime = node["content_type"].string() ?: node["mimeType"].string() ?: node["mime_type"].string()
                val inline = node["b64_json"].string() ?: node["base64"].string() ?: node["b64"].string()
                if (inline != null) {
                    outputs[inline.take(DEDUPE_KEY_LENGTH)] = EngineOutput.Inline(inline, mime ?: defaultMime)
                }
                node["url"].string()?.let { addUrl(it, mime) }
                node.forEach { (key, value) ->
                    if (key !in SCALAR_KEYS) visit(value, depth + 1)
                }
            }
            is JsonArray -> node.forEach { child ->
                val asString = child.string()
                if (asString != null) addUrl(asString, null) else visit(child, depth + 1)
            }
            is JsonPrimitive -> Unit
        }
    }

    visit(element, 0)
    return outputs.values.toList()
}

internal fun parseDataUri(uri: String): EngineOutput.Inline? {
    val comma = uri.indexOf(',')
    if (!uri.startsWith("data:") || comma == -1) return null
    val meta = uri.substring(DATA_PREFIX_LENGTH, comma)
    if (!meta.endsWith(";base64")) return null
    return EngineOutput.Inline(uri.substring(comma + 1), meta.removeSuffix(";base64").ifBlank { "image/png" })
}

internal fun guessMime(url: String, fallback: String): String {
    val path = url.substringBefore('?').lowercase()
    return when {
        path.endsWith(".mp4") -> "video/mp4"
        path.endsWith(".webm") -> "video/webm"
        path.endsWith(".mov") -> "video/quicktime"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".webp") -> "image/webp"
        path.endsWith(".png") -> "image/png"
        else -> fallback
    }
}

internal inline fun <T> timed(block: () -> T): Pair<T, Long> {
    val start = System.nanoTime()
    val value = block()
    return value to (System.nanoTime() - start) / NANOS_PER_MILLI
}

/** Maps a probe failure for display. Cancellation is rethrown so structured concurrency holds. */
internal fun Throwable.toFailedProbe(latencyMillis: Long? = null): ConnectionProbe = when (this) {
    is CancellationException -> throw this
    is VisionStudioException.AuthenticationException ->
        ConnectionProbe(false, "Authentication failed", listOfNotNull(message), latencyMillis)
    is VisionStudioException.TimeoutException ->
        ConnectionProbe(false, "Connection timed out", listOfNotNull(message), latencyMillis)
    is VisionStudioException.NetworkException ->
        ConnectionProbe(false, "Server unreachable", listOfNotNull(message), latencyMillis)
    is VisionStudioException.ProtocolException ->
        ConnectionProbe(false, "Protocol error", listOfNotNull(message), latencyMillis)
    is VisionStudioException.ProviderException ->
        ConnectionProbe(false, "Provider error${httpCode?.let { " ($it)" }.orEmpty()}", listOfNotNull(providerMessage), latencyMillis)
    else -> ConnectionProbe(false, "Connection failed", listOfNotNull(message), latencyMillis)
}

private val SCALAR_KEYS = setOf("b64_json", "base64", "b64", "url", "content_type", "mimeType", "mime_type")
private const val MAX_DEPTH = 6
private const val MAX_OUTPUTS = 16
private const val DEDUPE_KEY_LENGTH = 96
private const val DATA_PREFIX_LENGTH = 5
private const val NANOS_PER_MILLI = 1_000_000L
private const val VIDEO_LONG_EDGE = 1280
private const val VIDEO_ALIGNMENT = 16
