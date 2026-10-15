package com.visionstudio.core.network.engine.rest

import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.network.engine.EngineOutput
import com.visionstudio.core.network.engine.GenerationEngine
import com.visionstudio.core.network.engine.authHeaders
import com.visionstudio.core.network.engine.headers
import com.visionstudio.core.network.engine.isSameHost
import com.visionstudio.core.network.http.RestHttpClient
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.Request
import java.io.File

/** Shared plumbing for REST dialects: auth headers, downloads, and inline decoding. */
abstract class RestEngineBase(
    final override val config: EngineConfig,
    protected val apiKey: String?,
    protected val http: RestHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
) : GenerationEngine {

    protected val auth: Map<String, String> = config.authHeaders(apiKey)

    protected fun authorized(url: String): Request.Builder =
        Request.Builder().url(url).headers(auth)

    override suspend fun downloadTo(output: EngineOutput, target: File): Long = when (output) {
        is EngineOutput.Inline -> withContext(ioDispatcher) {
            val bytes = output.decode()
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            bytes.size.toLong()
        }
        is EngineOutput.Remote -> {
            // Only attach credentials to the provider's own host; CDN links are pre-signed.
            val builder = if (config.isSameHost(output.url)) authorized(output.url) else Request.Builder().url(output.url)
            http.downloadTo(builder.get().build(), target, config.slot)
        }
    }

    override suspend fun close() = Unit

    protected fun unsupported(what: String): Nothing =
        throw VisionStudioException.ValidationException(null, "$what is not supported by ${config.restDialect.displayName}")
}
