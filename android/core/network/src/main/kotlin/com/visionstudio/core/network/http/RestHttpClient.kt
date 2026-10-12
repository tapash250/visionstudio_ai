package com.visionstudio.core.network.http

import com.visionstudio.core.common.dispatchers.DefaultDispatcher
import com.visionstudio.core.common.dispatchers.IoDispatcher
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.error.toVisionStudioException
import com.visionstudio.core.model.EngineSlot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fully buffered HTTP response. Bodies here are small JSON payloads; media uses [RestHttpClient.downloadTo]. */
class HttpResult(
    val code: Int,
    val body: ByteArray,
    val contentType: String?,
) {
    val isSuccessful: Boolean get() = code in HTTP_OK_RANGE
    fun bodyText(): String = body.toString(Charsets.UTF_8)

    private companion object {
        val HTTP_OK_RANGE = 200..299
    }
}

/**
 * Coroutine facade over the shared [OkHttpClient]. Socket and body I/O run on [io], and JSON
 * (de)serialization runs on [default]. Transport and HTTP failures are thrown as
 * [VisionStudioException].
 */
@Singleton
class RestHttpClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @IoDispatcher private val io: CoroutineDispatcher,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    suspend fun execute(request: Request): HttpResult = withContext(io) {
        val response = try {
            okHttpClient.newCall(request).await()
        } catch (failure: IOException) {
            throw failure.toVisionStudioException()
        }
        response.use {
            HttpResult(
                code = it.code,
                body = it.body?.bytes() ?: ByteArray(0),
                contentType = it.header("Content-Type"),
            )
        }
    }

    /** Executes and decodes a JSON body, throwing a typed error on any non-2xx. */
    suspend fun executeJson(request: Request, slot: EngineSlot?): JsonElement {
        val result = execute(request)
        result.throwIfError(slot)
        return parseJson(result.bodyText())
    }

    suspend fun parseJson(text: String): JsonElement = withContext(default) {
        try {
            json.parseToJsonElement(text.ifBlank { "{}" })
        } catch (malformed: IllegalArgumentException) {
            throw VisionStudioException.ProtocolException(null, "Provider returned malformed JSON", malformed)
        }
    }

    suspend fun jsonBody(element: JsonElement): RequestBody = withContext(default) {
        json.encodeToString(JsonElement.serializer(), element).toRequestBody(JSON_MEDIA_TYPE)
    }

    /**
     * Streams a response body to [target] through a temporary sibling file, then renames it,
     * so a cancelled or failed download never leaves a truncated file at [target].
     */
    suspend fun downloadTo(request: Request, target: File, slot: EngineSlot?): Long = withContext(io) {
        val response = try {
            okHttpClient.newCall(request).await()
        } catch (failure: IOException) {
            throw failure.toVisionStudioException()
        }
        response.use {
            if (!it.isSuccessful) {
                HttpResult(it.code, it.body?.bytes() ?: ByteArray(0), it.header("Content-Type")).throwIfError(slot)
            }
            val body = it.body ?: throw VisionStudioException.ProviderException(it.code, "Empty media response")
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, "${target.name}.part")
            try {
                val written = body.byteStream().use { input ->
                    partial.outputStream().use { output -> input.copyTo(output, DOWNLOAD_BUFFER) }
                }
                if (!partial.renameTo(target)) {
                    throw VisionStudioException.StorageException("Could not move download into ${target.name}")
                }
                written
            } catch (failure: IOException) {
                throw VisionStudioException.StorageException("Download interrupted", failure)
            } finally {
                partial.delete()
            }
        }
    }

    companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val DOWNLOAD_BUFFER = 64 * 1024
    }
}

/** Maps a non-2xx [HttpResult] to the domain error hierarchy. */
fun HttpResult.throwIfError(slot: EngineSlot?) {
    if (isSuccessful) return
    val message = extractErrorMessage(bodyText())
    throw when (code) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
            VisionStudioException.AuthenticationException(slot, code, message ?: "Provider rejected the API key ($code)")
        else -> VisionStudioException.ProviderException(code, message)
    }
}

/** Pulls a human message out of the common provider error envelopes. */
internal fun extractErrorMessage(body: String): String? {
    if (body.isBlank()) return null
    val element = runCatching { Json.parseToJsonElement(body) }.getOrNull()
        ?: return body.trim().take(MAX_ERROR_LENGTH)
    fun JsonElement.text(): String? = (this as? JsonPrimitive)?.contentOrNull
    val obj = element as? JsonObject ?: return body.trim().take(MAX_ERROR_LENGTH)
    val error = obj["error"]
    return (error as? JsonObject)?.get("message")?.text()
        ?: error?.text()
        ?: obj["detail"]?.let { it.text() ?: it.toString() }
        ?: obj["message"]?.text()
        ?: body.trim().take(MAX_ERROR_LENGTH)
}

/** Closes the response if the caller is cancelled between delivery and resumption. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { response.close() }
            }
        },
    )
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val MAX_ERROR_LENGTH = 300
