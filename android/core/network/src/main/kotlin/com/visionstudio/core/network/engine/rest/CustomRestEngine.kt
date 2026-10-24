package com.visionstudio.core.network.engine.rest

import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.network.engine.ConnectionProbe
import com.visionstudio.core.network.engine.EngineProgress
import com.visionstudio.core.network.engine.EngineRequest
import com.visionstudio.core.network.engine.JobStatus
import com.visionstudio.core.network.engine.RemoteCancelResult
import com.visionstudio.core.network.engine.RemoteJob
import com.visionstudio.core.network.engine.Submission
import com.visionstudio.core.network.engine.decodePreviewPayload
import com.visionstudio.core.network.engine.extractOutputs
import com.visionstudio.core.network.engine.isSameHost
import com.visionstudio.core.network.engine.previewString
import com.visionstudio.core.network.engine.streamFraction
import com.visionstudio.core.network.engine.stringField
import com.visionstudio.core.mcp.transport.SseEvent
import com.visionstudio.core.network.engine.joinUrl
import com.visionstudio.core.network.engine.outputDimensions
import com.visionstudio.core.network.engine.promptWithDirectives
import com.visionstudio.core.network.engine.timed
import com.visionstudio.core.network.engine.toFailedProbe
import com.visionstudio.core.network.http.RestHttpClient
import com.visionstudio.core.network.http.throwIfError
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
 *
 * Cancellation is opt-in: if the envelope also carries `cancel_url` on the endpoint's own host,
 * cancelling sends `POST {cancel_url}` with an empty JSON object. Any 2xx means accepted, 404
 * means unknown and 409 or 410 mean it had already finished. Without one there is nothing to call.
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

    override suspend fun submit(request: EngineRequest, onProgress: (EngineProgress) -> Unit): Submission {
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
        var streamedResult: JsonElement? = null
        val request = authorized(config.endpointUrl)
            .header("Accept", "${RestHttpClient.EVENT_STREAM}, application/json")
            .post(http.jsonBody(payload))
            .build()
        val response = http.executeJsonOrEvents(request, config.slot) { event ->
            handleStreamEvent(event)?.let { update ->
                when (update) {
                    is StreamUpdate.Progress -> onProgress(update.progress)
                    is StreamUpdate.Result -> streamedResult = update.json
                }
            }
        } ?: return streamedResult
            ?.let { Submission.Completed(extractOutputs(it, defaultMime)) }
            ?: throw VisionStudioException.ProtocolException(null, "Event stream ended without a result")

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
                cancelUrl = envelope.string("cancel_url"),
            ),
        )
    }

    private sealed interface StreamUpdate {
        class Progress(val progress: EngineProgress) : StreamUpdate
        class Result(val json: JsonElement) : StreamUpdate
    }

    /**
     * Streaming protocol for self-hosted backends: each `data:` frame is a JSON object. Frames
     * with media outputs (or `event: result|complete|done`) carry the final result. Frames with
     * `progress`/`step` and an optional `preview` image report intermediate state. An `error`
     * event or failed `status` aborts the generation.
     */
    private suspend fun handleStreamEvent(event: SseEvent): StreamUpdate? {
        val data = event.data.trim()
        if (data.isEmpty() || data == "[DONE]") return null
        val json = runCatching { http.parseJson(data) }.getOrNull() as? JsonObject ?: return null
        val status = json.stringField("status")?.lowercase()
        if (event.event == "error" || status in FAILED_STATES) {
            throw VisionStudioException.ProviderException(
                null,
                json.stringField("error") ?: json.stringField("message") ?: "Generation failed",
            )
        }
        val outputs = extractOutputs(json, defaultMime)
        if (outputs.isNotEmpty() && (event.event in RESULT_EVENTS || status in DONE_STATES || json.streamFraction() == null)) {
            return StreamUpdate.Result(json)
        }
        return StreamUpdate.Progress(
            EngineProgress(
                fraction = json.streamFraction(),
                message = json.stringField("message") ?: status,
                preview = json.previewString()?.let(::decodePreviewPayload),
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

    override suspend fun cancel(job: RemoteJob): RemoteCancelResult {
        // The request carries the API key, so it only ever goes to the configured endpoint's host.
        val url = job.cancelUrl?.takeIf { config.isSameHost(it) } ?: return RemoteCancelResult.UNSUPPORTED
        val result = http.execute(authorized(url).post(http.jsonBody(JsonObject(emptyMap()))).build())
        return when {
            result.isSuccessful -> RemoteCancelResult.REQUESTED
            result.code == HTTP_NOT_FOUND -> RemoteCancelResult.NOT_FOUND
            result.code == HTTP_CONFLICT || result.code == HTTP_GONE -> RemoteCancelResult.ALREADY_FINISHED
            else -> {
                result.throwIfError(config.slot)
                RemoteCancelResult.REQUESTED // not reached: throwIfError throws for every non-2xx
            }
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        val RESULT_EVENTS = setOf("result", "complete", "completed", "done")
        val DONE_STATES = setOf("completed", "succeeded", "success", "done", "finished")
        val FAILED_STATES = setOf("failed", "error", "cancelled", "canceled")
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val HTTP_GONE = 410
        val HTTP_SERVER_ERRORS = 500..599
        const val PERCENT = 100.0
    }
}
