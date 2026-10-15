package com.visionstudio.core.network.engine

import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.GenerationParameters
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Base64

/**
 * Protocol-agnostic contract every provider adapter implements. Queue-based providers return
 * [Submission.Queued] and are then driven by [poll]. Synchronous providers return
 * [Submission.Completed] directly.
 */
interface GenerationEngine {
    val config: EngineConfig

    /** Lightweight authenticated round-trip used by Settings "Test Connection". Never throws. */
    suspend fun testConnection(): ConnectionProbe

    /**
     * [onProgress] receives streamed updates (fraction, status text, low-resolution previews)
     * when the provider supports them. It is invoked from I/O threads and must not block.
     */
    suspend fun submit(request: EngineRequest, onProgress: (EngineProgress) -> Unit = {}): Submission

    suspend fun poll(job: RemoteJob): JobStatus

    /** Writes [output] to [target], fetching it with provider credentials if needed. Returns bytes written. */
    suspend fun downloadTo(output: EngineOutput, target: File): Long

    suspend fun close()
}

/** Raw media the caller has already read (from the Photo Picker, the mask rasterizer and so on). */
class BinaryPayload(val bytes: ByteArray, val mimeType: String) {
    fun base64(): String = Base64.getEncoder().encodeToString(bytes)
    fun dataUri(): String = "data:$mimeType;base64,${base64()}"

    val fileExtension: String
        get() = when (mimeType) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "video/mp4" -> "mp4"
            else -> "png"
        }
}

/** An intermediate update while a provider renders. [preview] is a low-fidelity latent decode. */
class EngineProgress(
    val fraction: Float? = null,
    val message: String? = null,
    val preview: BinaryPayload? = null,
) {
    init {
        fraction?.let { require(it in 0f..1f) { "fraction must be in 0..1, was $it" } }
    }
}

data class EngineRequest(
    val slot: EngineSlot,
    val parameters: GenerationParameters,
    val sourceImage: BinaryPayload? = null,
    val mask: BinaryPayload? = null,
)

sealed interface Submission {
    data class Completed(val outputs: List<EngineOutput>) : Submission
    data class Queued(val job: RemoteJob) : Submission
}

/** Handle for a provider-side job. Serializable so WorkManager can persist it between polls. */
@Serializable
data class RemoteJob(
    val id: String,
    val statusUrl: String,
    val resultUrl: String? = null,
    val cancelUrl: String? = null,
)

sealed interface JobStatus {
    data class InProgress(
        val progress: Float? = null,
        val queuePosition: Int? = null,
        val message: String? = null,
    ) : JobStatus

    data class Completed(val outputs: List<EngineOutput>) : JobStatus

    data class Failed(val reason: String, val retryable: Boolean = false) : JobStatus
}

sealed interface EngineOutput {
    val mimeType: String

    data class Remote(val url: String, override val mimeType: String) : EngineOutput

    /** Inline payload (OpenAI `b64_json`, MCP image content, data URIs). */
    class Inline(val base64: String, override val mimeType: String) : EngineOutput {
        fun decode(): ByteArray = Base64.getDecoder().decode(base64)
        override fun toString(): String = "Inline(mimeType=$mimeType, base64Length=${base64.length})"
    }

    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

/** Outcome of a connection test, shaped for direct display in Settings. */
data class ConnectionProbe(
    val success: Boolean,
    val headline: String,
    val details: List<String> = emptyList(),
    val latencyMillis: Long? = null,
)
