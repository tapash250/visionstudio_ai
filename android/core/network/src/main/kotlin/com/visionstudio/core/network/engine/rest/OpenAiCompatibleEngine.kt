package com.visionstudio.core.network.engine.rest

import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.model.Dimensions
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.network.engine.ConnectionProbe
import com.visionstudio.core.network.engine.EngineRequest
import com.visionstudio.core.network.engine.JobStatus
import com.visionstudio.core.network.engine.RemoteJob
import com.visionstudio.core.network.engine.Submission
import com.visionstudio.core.network.engine.EngineOutput
import com.visionstudio.core.network.engine.extractOutputs
import com.visionstudio.core.network.engine.joinUrl
import com.visionstudio.core.network.engine.outputDimensions
import com.visionstudio.core.network.engine.promptWithDirectives
import com.visionstudio.core.network.engine.requireSourceImage
import com.visionstudio.core.network.engine.timed
import com.visionstudio.core.network.engine.toFailedProbe
import com.visionstudio.core.network.http.RestHttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.abs

/**
 * OpenAI-compatible dialect: `/images/generations`, `/images/edits` and `/videos`.
 * [EngineConfig.endpointUrl] is the API base (for example `https://api.openai.com/v1`).
 * Compatible servers such as LocalAI or vLLM accept arbitrary sizes; api.openai.com sizes are
 * snapped to the nearest supported preset.
 */
class OpenAiCompatibleEngine(
    config: EngineConfig,
    apiKey: String?,
    http: RestHttpClient,
    ioDispatcher: CoroutineDispatcher,
) : RestEngineBase(config, apiKey, http, ioDispatcher) {

    private val base = config.endpointUrl.trimEnd('/')
    private val isOfficialApi = base.toHttpUrlOrNull()?.host == "api.openai.com"

    override suspend fun testConnection(): ConnectionProbe {
        var latency: Long? = null
        return runCatching {
            val (models, elapsed) = timed {
                http.executeJson(authorized(joinUrl(base, "models")).get().build(), config.slot)
            }
            latency = elapsed
            val ids = (models as? JsonObject)?.get("data")?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.get("id")?.let { id -> (id as? JsonPrimitive)?.contentOrNull } }
                .orEmpty()
            val modelListed = config.modelId in ids
            ConnectionProbe(
                success = true,
                headline = if (modelListed) "Connected · model available" else "Connected · key accepted",
                details = buildList {
                    add("${ids.size} models visible to this key")
                    if (!modelListed && ids.isNotEmpty()) add("'${config.modelId}' is not in the model list")
                },
                latencyMillis = elapsed,
            )
        }.getOrElse { it.toFailedProbe(latency) }
    }

    override suspend fun submit(request: EngineRequest, onProgress: (Float) -> Unit): Submission =
        when (request.slot) {
            EngineSlot.TEXT_TO_IMAGE -> generateImage(request)
            EngineSlot.IMAGE_EDIT -> editImage(request)
            EngineSlot.VIDEO_STUDIO -> createVideo(request)
        }

    private suspend fun generateImage(request: EngineRequest): Submission {
        val params = request.parameters
        val body = buildJsonObject {
            put("model", config.modelId)
            put("prompt", params.promptWithDirectives())
            put("n", 1)
            put("size", sizeFor(params.outputDimensions(request.slot)))
        }
        val response = http.executeJson(
            authorized(joinUrl(base, "images/generations")).post(http.jsonBody(body)).build(),
            config.slot,
        )
        return Submission.Completed(requireOutputs(extractOutputs(response, IMAGE_MIME)))
    }

    private suspend fun editImage(request: EngineRequest): Submission {
        val source = requireSourceImage(request)
        val params = request.parameters
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", config.modelId)
            .addFormDataPart("prompt", params.prompt)
            .addFormDataPart("n", "1")
            .addFormDataPart("size", sizeFor(params.outputDimensions(request.slot)))
            .addFormDataPart("image", "source.${source.fileExtension}", source.bytes.toRequestBody(source.mimeType.toMediaType()))
            .apply {
                request.mask?.let { mask ->
                    addFormDataPart("mask", "mask.png", mask.bytes.toRequestBody(mask.mimeType.toMediaType()))
                }
            }
            .build()
        val response = http.executeJson(authorized(joinUrl(base, "images/edits")).post(form).build(), config.slot)
        return Submission.Completed(requireOutputs(extractOutputs(response, IMAGE_MIME)))
    }

    private suspend fun createVideo(request: EngineRequest): Submission {
        val params = request.parameters
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", config.modelId)
            .addFormDataPart("prompt", params.promptWithDirectives())
            .addFormDataPart("size", params.outputDimensions(request.slot).toString())
            .apply {
                params.durationSeconds?.let { addFormDataPart("seconds", it.toString()) }
                request.sourceImage?.let { image ->
                    addFormDataPart(
                        "input_reference",
                        "reference.${image.fileExtension}",
                        image.bytes.toRequestBody(image.mimeType.toMediaType()),
                    )
                }
            }
            .build()
        val response = http.executeJson(authorized(joinUrl(base, "videos")).post(form).build(), config.slot).jsonObject
        val id = (response["id"] as? JsonPrimitive)?.contentOrNull
            ?: throw VisionStudioException.ProtocolException(null, "Video job response had no id")
        return Submission.Queued(
            RemoteJob(
                id = id,
                statusUrl = joinUrl(base, "videos/$id"),
                resultUrl = joinUrl(base, "videos/$id/content"),
            ),
        )
    }

    override suspend fun poll(job: RemoteJob): JobStatus {
        val status = http.executeJson(authorized(job.statusUrl).get().build(), config.slot).jsonObject
        val state = (status["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val progress = (status["progress"] as? JsonPrimitive)?.doubleOrNull?.let { (it / PERCENT).toFloat().coerceIn(0f, 1f) }
        return when (state) {
            "completed", "succeeded" -> JobStatus.Completed(
                listOf(EngineOutput.Remote(job.resultUrl ?: joinUrl(job.statusUrl, "content"), VIDEO_MIME)),
            )
            "failed", "cancelled", "expired" -> JobStatus.Failed(
                reason = (status["error"] as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
                    ?: "Video job $state",
            )
            else -> JobStatus.InProgress(progress = progress, message = state.ifBlank { null })
        }
    }

    private fun sizeFor(dimensions: Dimensions): String {
        if (!isOfficialApi) return dimensions.toString()
        val target = dimensions.width.toFloat() / dimensions.height
        return OFFICIAL_IMAGE_SIZES.minBy { abs(it.width.toFloat() / it.height - target) }.toString()
    }

    private fun requireOutputs(outputs: List<EngineOutput>): List<EngineOutput> =
        outputs.ifEmpty { throw VisionStudioException.ProtocolException(null, "Provider response contained no images") }

    private companion object {
        const val IMAGE_MIME = "image/png"
        const val VIDEO_MIME = "video/mp4"
        const val PERCENT = 100.0
        val OFFICIAL_IMAGE_SIZES = listOf(Dimensions(1024, 1024), Dimensions(1536, 1024), Dimensions(1024, 1536))
    }
}
