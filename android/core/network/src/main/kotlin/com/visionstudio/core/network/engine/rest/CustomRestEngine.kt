package com.visionstudio.core.network.engine.rest

import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.network.engine.ConnectionProbe
import com.visionstudio.core.network.engine.EngineRequest
import com.visionstudio.core.network.engine.JobStatus
import com.visionstudio.core.network.engine.RemoteJob
import com.visionstudio.core.network.engine.Submission
import com.visionstudio.core.network.engine.extractOutputs
import com.visionstudio.core.network.engine.joinUrl
import com.visionstudio.core.network.engine.outputDimensions
import com.visionstudio.core.network.engine.promptWithDirectives
import com.visionstudio.core.network.engine.timed
import com.visionstudio.core.network.engine.toFailedProbe
import com.visionstudio.core.network.http.RestHttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Generic JSON dialect for self-hosted backends. Sends one flat payload and accepts either an
 * immediate media response or a job envelope (`id`/`job_id`/`request_id` plus an optional
 * `status_url`), which is then polled.
 */
class CustomRestEngine(
    config: EngineConfig,
    apiKey: String?,
    http: RestHttpClient,
    ioDispatcher: CoroutineDispatcher,
) : RestEngineBase(config, apiKey, http, ioDispatcher) {

    private val defaultMime = if (config.slot == EngineSlot.VIDEO_STUDIO) "video/mp4" else "image/png"

    override suspend fun testConnection(): ConnectionProbe {
        var latency: Long? = null
        return runCatching {
            val (result, elapsed) = timed { http.execute(authorized(config.endpointUrl).get().build()) }
            latency = elapsed
            when (result.code) {
                HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
                    ConnectionProbe(false, "Authentication failed", listOf("HTTP ${result.code}"), elapsed)
                in HTTP_SERVER_ERRORS ->
                    ConnectionProbe(false, "Server error (HTTP ${result.code})", latencyMillis = elapsed)
                else -> ConnectionProbe(
                    success = true,
                    headline = "Endpoint reachable",
                    // POST-only endpoints commonly answer GET with 404/405; that still proves reachability and auth.
                    details = listOf("HTTP ${result.code} on GET"),
                    latencyMillis = elapsed,
                )
            }
        }.getOrElse { it.toFailedProbe(latency) }
    }

    override suspend fun submit(request: EngineRequest, onProgress: (Float) -> Unit): Submission {
        val params = request.parameters
        val dimensions = params.outputDimensions(request.slot)
        val payload = buildJsonObject {
            put("model", config.modelId)
            put("task", request.slot.name.lowercase())
            put("prompt", params.promptWithDirectives())
            put("negative_prompt", params.negativePrompt)
            put("width", dimensions.width)
            put("height", dimensions.height)
            put("aspect_ratio", params.aspectRatio.label)
            put("steps", params.steps)
            put("guidance_scale", params.guidanceScale)
            params.seed?.let { put("seed", it) }
            params.strength?.let { put("strength", it) }
            params.durationSeconds?.let { put("duration_seconds", it) }
            params.cameraMotion?.let { put("camera_motion", it.name.lowercase()) }
            request.sourceImage?.let { put("image", it.dataUri()) }
            request.mask?.let { put("mask", it.dataUri()) }
        }
        val response = http.executeJson(authorized(config.endpointUrl).post(http.jsonBody(payload)).build(), config.slot)

        val outputs = extractOutputs(response, defaultMime)
        if (outputs.isNotEmpty()) return Submission.Completed(outputs)

        val envelope = response as? JsonObject
        val jobId = envelope?.let { it.string("id") ?: it.string("job_id") ?: it.string("request_id") }
            ?: throw VisionStudioException.ProtocolException(null, "Response contained neither media nor a job id")
        return Submission.Queued(
            RemoteJob(
                id = jobId,
                statusUrl = envelope.string("status_url") ?: joinUrl(config.endpointUrl, jobId),
                resultUrl = envelope.string("result_url") ?: envelope.string("response_url"),
            ),
        )
    }

    override suspend fun poll(job: RemoteJob): JobStatus {
        val status = http.executeJson(authorized(job.statusUrl).get().build(), config.slot)
        val envelope = status as? JsonObject
        val state = envelope?.string("status")?.lowercase().orEmpty()
        return when (state) {
            in DONE_STATES -> {
                val source: JsonElement = job.resultUrl
                    ?.let { http.executeJson(authorized(it).get().build(), config.slot) }
                    ?: status
                val outputs = extractOutputs(source, defaultMime)
                if (outputs.isEmpty()) JobStatus.Failed("Job finished without media") else JobStatus.Completed(outputs)
            }
            in FAILED_STATES -> JobStatus.Failed(envelope?.string("error") ?: envelope?.string("message") ?: "Job $state")
            else -> JobStatus.InProgress(
                progress = (envelope?.get("progress") as? JsonPrimitive)?.doubleOrNull?.let { raw ->
                    (if (raw > 1.0) raw / PERCENT else raw).toFloat().coerceIn(0f, 1f)
                },
                message = state.ifBlank { null },
            )
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        val DONE_STATES = setOf("completed", "succeeded", "success", "done", "finished")
        val FAILED_STATES = setOf("failed", "error", "cancelled", "canceled")
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        val HTTP_SERVER_ERRORS = 500..599
        const val PERCENT = 100.0
    }
}
