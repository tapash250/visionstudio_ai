package com.visionstudio.core.network.engine

import android.util.Log
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
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
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

/** True when [a] and [b] are URLs on the same host. */
internal fun sameHost(a: String, b: String): Boolean {
    val host = a.toHttpUrlOrNull()?.host ?: return false
    return b.toHttpUrlOrNull()?.host.equals(host, ignoreCase = true)
}

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
/**
 * Turns a failed connection check into a short, specific headline plus redacted details. The
 * whole cause chain is inspected, since transport errors usually arrive wrapped (for example an
 * SSLHandshakeException inside a NetworkException). Full detail goes to logcat at DEBUG under
 * [DIAGNOSTICS_TAG], off unless enabled (`adb shell setprop log.tag.VisionStudioNet DEBUG`), with
 * [secrets] and anything that looks like a credential removed.
 */
internal fun Throwable.toFailedProbe(latencyMillis: Long? = null, secrets: Collection<String> = emptyList()): ConnectionProbe {
    if (this is CancellationException) throw this
    val diagnosis = diagnose(this)
    val details = listOfNotNull(diagnosis.hint, diagnosis.detail).map { redact(it, secrets) }
    if (Log.isLoggable(DIAGNOSTICS_TAG, Log.DEBUG)) {
        Log.d(DIAGNOSTICS_TAG, redact("${diagnosis.headline}: ${causeChain(this).joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }}", secrets))
    }
    return ConnectionProbe(false, diagnosis.headline, details, latencyMillis)
}

internal data class Diagnosis(val headline: String, val hint: String?, val detail: String?)

internal fun diagnose(error: Throwable): Diagnosis {
    val chain = causeChain(error)
    fun has(predicate: (Throwable) -> Boolean) = chain.any(predicate)
    val root = chain.last().message

    // Transport first: these explain everything layered above them.
    chain.firstOrNull { it is UnknownHostException }?.let {
        return Diagnosis("Host not found", "DNS could not resolve the server's name. Check the URL and that the device is online.", it.message)
    }
    chain.firstOrNull { it is SSLPeerUnverifiedException }?.let {
        return Diagnosis("TLS hostname mismatch", "The server's certificate is not valid for this host name.", it.message)
    }
    if (has { it is CertificateException || it is CertPathValidatorException || it is SSLHandshakeException }) {
        val trust = has { it is CertPathValidatorException || it.message.orEmpty().contains("trust anchor", ignoreCase = true) || it.message.orEmpty().contains("PKIX") }
        return if (trust) {
            Diagnosis("TLS certificate not trusted", "The server's certificate chain does not lead to a trusted certificate authority.", root)
        } else {
            Diagnosis("TLS handshake failed", "A secure connection could not be set up with the server.", root)
        }
    }
    if (has { it is SSLException }) {
        return Diagnosis("TLS error", "The secure connection to the server failed.", root)
    }
    if (error is VisionStudioException.TimeoutException || has { it is InterruptedIOException }) {
        return Diagnosis("Connection timed out", "The server did not answer in time. It may be busy or cold-starting; try again.", error.message ?: root)
    }
    if (has { it is ConnectException || it is NoRouteToHostException }) {
        return Diagnosis("Server unreachable", "The connection was refused or the network is unavailable.", root)
    }
    return when (error) {
        is VisionStudioException.AuthenticationException ->
            Diagnosis("Authentication failed (HTTP ${error.httpCode ?: "401/403"})", "Check the API key and the Authorization setting.", error.message)
        is VisionStudioException.UnsupportedTransportException ->
            Diagnosis("Unsupported MCP transport", error.message, error.cause?.message)
        is VisionStudioException.ProviderException -> when (error.httpCode) {
            HTTP_NOT_FOUND -> Diagnosis("Endpoint not found (HTTP 404)", "Nothing answers at this URL. Check the path.", error.providerMessage)
            HTTP_METHOD_NOT_ALLOWED -> Diagnosis("Method not allowed (HTTP 405)", "The URL exists but does not accept this request; it may be the wrong endpoint.", error.providerMessage)
            else -> Diagnosis("Provider error${error.httpCode?.let { " (HTTP $it)" }.orEmpty()}", null, error.providerMessage ?: error.message)
        }
        is VisionStudioException.ProtocolException ->
            Diagnosis(if (error.rpcErrorCode != null) "MCP error ${error.rpcErrorCode}" else "Protocol error", null, error.message)
        is VisionStudioException.NetworkException -> Diagnosis("Network error", null, error.message)
        else -> Diagnosis("Connection failed", null, error.message ?: error.javaClass.simpleName)
    }
}

private fun causeChain(error: Throwable): List<Throwable> =
    generateSequence(error) { current -> current.cause?.takeIf { it !== current } }.take(MAX_CAUSE_DEPTH).toList()

/** Removes [secrets] and credential-shaped values (auth headers, Hugging Face and API tokens). */
internal fun redact(text: String, secrets: Collection<String> = emptyList()): String {
    var out = text
    secrets.filter { it.length >= MIN_SECRET_LENGTH }.forEach { out = out.replace(it, REDACTED) }
    return REDACTION_PATTERNS.fold(out) { acc, pattern -> pattern.replace(acc) { it.groupValues[1] + REDACTED } }
}

private val REDACTION_PATTERNS = listOf(
    Regex("""(?i)(authorization\s*[:=]\s*)\S+(\s+\S+)?"""),
    Regex("""(?i)(\b(?:bearer|key)\s+)[A-Za-z0-9._~+/=-]{8,}"""),
    Regex("""()\bhf_[A-Za-z0-9]{8,}"""),
    Regex("""()\bsk-[A-Za-z0-9_-]{8,}"""),
    Regex("""(?i)([?&](?:token|key|api_key|access_token)=)[^&\s]+"""),
)
private const val REDACTED = "‹redacted›"
private const val MIN_SECRET_LENGTH = 4
private const val MAX_CAUSE_DEPTH = 8
private const val HTTP_NOT_FOUND = 404
private const val HTTP_METHOD_NOT_ALLOWED = 405

/** Logcat tag for connection diagnostics; DEBUG output is off unless enabled per device. */
const val DIAGNOSTICS_TAG: String = "VisionStudioNet"

private val SCALAR_KEYS = setOf("b64_json", "base64", "b64", "url", "content_type", "mimeType", "mime_type")
private const val MAX_DEPTH = 6
private const val MAX_OUTPUTS = 16
private const val DEDUPE_KEY_LENGTH = 96
private const val DATA_PREFIX_LENGTH = 5
private const val NANOS_PER_MILLI = 1_000_000L
private const val VIDEO_LONG_EDGE = 1280
private const val VIDEO_ALIGNMENT = 16
