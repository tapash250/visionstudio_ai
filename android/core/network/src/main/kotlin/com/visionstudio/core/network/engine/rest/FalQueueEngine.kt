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
import com.visionstudio.core.network.engine.extractOutputs
import com.visionstudio.core.network.engine.joinUrl
import com.visionstudio.core.network.engine.outputDimensions
import com.visionstudio.core.network.engine.promptWithDirectives
import com.visionstudio.core.network.engine.requireSourceImage
import com.visionstudio.core.network.engine.sameHost
import com.visionstudio.core.network.engine.timed
import com.visionstudio.core.network.engine.toFailedProbe
import com.visionstudio.core.network.http.RestHttpClient
import com.visionstudio.core.network.http.throwIfError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Fal.ai queue dialect. Submissions go to `POST {base}/{modelId}`, which returns `request_id`,
 * `status_url` and `response_url`. Polling `status_url` reports `IN_QUEUE` → `IN_PROGRESS` →
 * `COMPLETED`, and `response_url` then returns the model output. `PUT cancel_url` asks Fal to stop
 * a request: a queued one is dropped, a running one is signalled and may still complete.
 * [EngineConfig.endpointUrl] defaults to `https://queue.fal.run`.
 */
class FalQueueEngine(
    config: EngineConfig,
    apiKey: String?,
    http: RestHttpClient,
    ioDispatcher: CoroutineDispatcher,
) : RestEngineBase(config, apiKey, http, ioDispatcher) {

    private val base = config.endpointUrl.ifBlank { DEFAULT_BASE }.trimEnd('/')
    private val modelPath = config.modelId.trim('/')

    override suspend fun testConnection(): ConnectionProbe {
        var latency: Long? = null
        return runCatching {
            // Fal has no key-introspection endpoint. Querying a nil request id authenticates
            // without spending credits: 401/403 means a bad key, 404/422 means the key is good.
            val (result, elapsed) = timed {
                http.execute(authorized(joinUrl(base, "$modelPath/requests/$NIL_UUID/status")).get().build())
            }
            latency = elapsed
            when (result.code) {
                HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> ConnectionProbe(
                    success = false,
                    headline = "Authentication failed",
                    details = listOf("Fal.ai rejected the key (HTTP ${result.code})"),
                    latencyMillis = elapsed,
                )
                in HTTP_SERVER_ERRORS -> ConnectionProbe(false, "Fal.ai unavailable (HTTP ${result.code})", latencyMillis = elapsed)
                else -> ConnectionProbe(
                    success = true,
                    headline = "Connected · key accepted",
                    details = listOf("Queue reachable for $modelPath"),
                    latencyMillis = elapsed,
                )
            }
        }.getOrElse { it.toFailedProbe(latency) }
    }

    override suspend fun submit(request: EngineRequest, onProgress: (EngineProgress) -> Unit): Submission {
        val params = request.parameters
        val dimensions = params.outputDimensions(request.slot)
        val input = buildJsonObject {
            put("prompt", params.promptWithDirectives())
            if (params.negativePrompt.isNotBlank()) put("negative_prompt", params.negativePrompt)
            params.seed?.let { put("seed", it) }
            when (request.slot) {
                EngineSlot.TEXT_TO_IMAGE, EngineSlot.IMAGE_EDIT -> {
                    putJsonObject("image_size") {
                        put("width", dimensions.width)
                        put("height", dimensions.height)
                    }
                    put("num_inference_steps", params.steps)
                    put("guidance_scale", params.guidanceScale)
                    put("num_images", 1)
                    put("enable_safety_checker", true)
                }
                EngineSlot.VIDEO_STUDIO -> {
                    put("aspect_ratio", params.aspectRatio.label)
                    params.durationSeconds?.let { put("duration", it.toString()) }
                }
            }
            if (request.slot == EngineSlot.IMAGE_EDIT) {
                put("image_url", requireSourceImage(request).dataUri())
                request.mask?.let { put("mask_url", it.dataUri()) }
                params.strength?.let { put("strength", it) }
            } else if (request.slot == EngineSlot.VIDEO_STUDIO) {
                request.sourceImage?.let { put("image_url", it.dataUri()) }
            }
        }

        val response = http.executeJson(
            authorized(joinUrl(base, modelPath)).post(http.jsonBody(input)).build(),
            config.slot,
        ).jsonObject
        val requestId = response.string("request_id")
            ?: throw VisionStudioException.ProtocolException(null, "Fal.ai did not return a request_id")
        return Submission.Queued(
            RemoteJob(
                id = requestId,
                statusUrl = response.string("status_url") ?: joinUrl(base, "$modelPath/requests/$requestId/status"),
                resultUrl = response.string("response_url") ?: joinUrl(base, "$modelPath/requests/$requestId"),
                cancelUrl = response.string("cancel_url"),
            ),
        )
    }

    override suspend fun poll(job: RemoteJob): JobStatus {
        val status = http.executeJson(authorized(job.statusUrl).get().build(), config.slot).jsonObject
        return when (val state = status.string("status")) {
            "IN_QUEUE" -> JobStatus.InProgress(
                queuePosition = (status["queue_position"] as? JsonPrimitive)?.intOrNull,
                message = "Queued",
            )
            "IN_PROGRESS" -> JobStatus.InProgress(message = "Rendering")
            "COMPLETED" -> fetchResult(job)
            else -> JobStatus.Failed(reason = status.string("error") ?: "Unexpected Fal.ai status '$state'")
        }
    }

    /**
     * `PUT {cancel_url}`: 202 accepted, 400 already completed, 404 unknown. The stored URL is used
     * only if it is on the queue's own host, since it carries the API key; otherwise the
     * documented path is rebuilt from the request id.
     */
    override suspend fun cancel(job: RemoteJob): RemoteCancelResult {
        val url = job.cancelUrl?.takeIf { sameHost(it, base) } ?: joinUrl(base, "$modelPath/requests/${job.id}/cancel")
        val result = http.execute(authorized(url).put(ByteArray(0).toRequestBody()).build())
        return when {
            result.isSuccessful -> RemoteCancelResult.REQUESTED
            result.code == HTTP_BAD_REQUEST -> RemoteCancelResult.ALREADY_FINISHED
            result.code == HTTP_NOT_FOUND -> RemoteCancelResult.NOT_FOUND
            else -> {
                result.throwIfError(config.slot)
                RemoteCancelResult.REQUESTED // not reached: throwIfError throws for every non-2xx
            }
        }
    }

    private suspend fun fetchResult(job: RemoteJob): JobStatus {
        val resultUrl = job.resultUrl ?: joinUrl(base, "$modelPath/requests/${job.id}")
        return try {
            val result = http.executeJson(authorized(resultUrl).get().build(), config.slot)
            val defaultMime = if (config.slot == EngineSlot.VIDEO_STUDIO) "video/mp4" else "image/png"
            val outputs = extractOutputs(result, defaultMime)
            if (outputs.isEmpty()) JobStatus.Failed("Fal.ai result contained no media") else JobStatus.Completed(outputs)
        } catch (provider: VisionStudioException.ProviderException) {
            // Model-side failures (validation, NSFW filter, OOM) surface on the result fetch.
            JobStatus.Failed(provider.providerMessage ?: provider.message.orEmpty(), retryable = provider.isRetryable)
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val DEFAULT_BASE = "https://queue.fal.run"
        const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
        val HTTP_SERVER_ERRORS = 500..599
    }
}
