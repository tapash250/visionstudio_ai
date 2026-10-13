package com.visionstudio.core.network.engine.rest

import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.model.Dimensions
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.network.engine.ConnectionProbe
import com.visionstudio.core.network.engine.EngineProgress
import com.visionstudio.core.network.engine.EngineRequest
import com.visionstudio.core.network.engine.JobStatus
import com.visionstudio.core.network.engine.RemoteJob
import com.visionstudio.core.network.engine.Submission
import com.visionstudio.core.network.engine.EngineOutput
import com.visionstudio.core.network.engine.decodePreviewPayload
import com.visionstudio.core.network.engine.extractOutputs
import com.visionstudio.core.network.engine.stringField
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
import kotlinx.serialization.json.intOrNull
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

    override suspend fun submit(request: EngineRequest, onProgress: (EngineProgress) -> Unit): Submission =
        when (request.slot) {
            EngineSlot.TEXT_TO_IMAGE -> generateImage(request, onProgress)
            EngineSlot.IMAGE_EDIT -> editImage(request)
            EngineSlot.VIDEO_STUDIO -> createVideo(request)
        }

    private suspend fun generateImage(request: EngineRequest, onProgress: (EngineProgress) -> Unit): Submission {
        val params = request.parameters
        val stream = supportsPartialImages
        val body = buildJsonObject {
            put("model", config.modelId)
            put("prompt", params.promptWithDirectives())
            put("n", 1)
            put("size", sizeFor(params.outputDimensions(request.slot)))
            if (stream) {
                put("stream", true)
                put("partial_images", PARTIAL_IMAGES)
            }
        }
        var final: EngineOutput? = null
        val response = http.executeJsonOrEvents(
            authorized(joinUrl(base, "images/generations")).post(http.jsonBody(body)).build(),
            config.slot,
        ) { event ->
            val json = runCatching { http.parseJson(event.data) }.getOrNull() as? JsonObject ?: return@executeJsonOrEvents
            val b64 = json.stringField("b64_json") ?: return@executeJsonOrEvents
            when (json.stringField("type") ?: event.event) {
                EVENT_PARTIAL -> {
                    val index = (json["partial_image_index"] as? JsonPrimitive)?.intOrNull ?: 0
                    onProgress(
                        EngineProgress(
                            fraction = ((index + 1f) / (PARTIAL_IMAGES + 1f)).coerceIn(0f, 1f),
                            message = "Refining",
                            preview = decodePreviewPayload(b64),
                        ),
                    )
                }
                EVENT_COMPLETED -> final = EngineOutput.Inline(b64, IMAGE_MIME)
            }
        }
        if (response == null) {
            val output = final ?: throw VisionStudioException.ProtocolException(null, "Image stream ended without a final image")
            return Submission.Completed(listOf(output))
        }
        return Submission.Completed(requireOutputs(extractOutputs(response, IMAGE_MIME)))
    }

    /** Only api.openai.com gpt-image models stream partial images; compatible servers may reject the flags. */
    private val supportsPartialImages: Boolean
        get() = isOfficialApi && config.modelId.startsWith("gpt-image")

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
        const val PARTIAL_IMAGES = 2
        const val EVENT_PARTIAL = "image_generation.partial_image"
        const val EVENT_COMPLETED = "image_generation.completed"
        val OFFICIAL_IMAGE_SIZES = listOf(Dimensions(1024, 1024), Dimensions(1536, 1024), Dimensions(1024, 1536))
    }
}
