package com.visionstudio.core.common.error

import com.visionstudio.core.model.EngineSlot
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Root of every domain-level failure surfaced to the UI. */
sealed class VisionStudioException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Whether repeating the same operation could reasonably succeed. */
    open val isRetryable: Boolean get() = false

    /** Connectivity failure: DNS, socket reset, TLS handshake, offline. */
    class NetworkException(
        message: String = "Network unavailable",
        cause: Throwable? = null,
    ) : VisionStudioException(message, cause) {
        override val isRetryable: Boolean get() = true
    }

    /** Request exceeded its deadline (connect, read, or overall job polling budget). */
    class TimeoutException(
        message: String = "The request timed out",
        cause: Throwable? = null,
    ) : VisionStudioException(message, cause) {
        override val isRetryable: Boolean get() = true
    }

    /** Provider rejected the credential (HTTP 401/403 or MCP auth error). */
    class AuthenticationException(
        val slot: EngineSlot?,
        val httpCode: Int?,
        message: String = "Authentication failed",
        cause: Throwable? = null,
    ) : VisionStudioException(message, cause)

    /** Provider answered but reported a failure (HTTP 4xx/5xx with body, job failed, quota). */
    class ProviderException(
        val httpCode: Int?,
        val providerMessage: String?,
        message: String = providerMessage ?: "Provider error${httpCode?.let { " ($it)" }.orEmpty()}",
        cause: Throwable? = null,
    ) : VisionStudioException(message, cause) {
        override val isRetryable: Boolean
            get() = httpCode == null || httpCode == HTTP_TOO_MANY_REQUESTS || httpCode >= HTTP_SERVER_ERROR

        private companion object {
            const val HTTP_TOO_MANY_REQUESTS = 429
            const val HTTP_SERVER_ERROR = 500
        }
    }

    /** Malformed frames or JSON-RPC error objects (MCP), or undecodable REST payloads. */
    class ProtocolException(
        val rpcErrorCode: Int?,
        message: String,
        cause: Throwable? = null,
    ) : VisionStudioException(message, cause)

    /** A slot was used before being configured, or user input failed validation. */
    class ValidationException(
        val field: String?,
        message: String,
    ) : VisionStudioException(message)

    /**
     * Keystore / EncryptedSharedPreferences could not read or write a secret. The stored secrets
     * were kept unless the store reported a recovery; [requiresUserAuthentication] means the
     * device must be unlocked before retrying.
     */
    class SecureStorageException(
        message: String,
        cause: Throwable? = null,
        val requiresUserAuthentication: Boolean = false,
        private val retryable: Boolean = false,
    ) : VisionStudioException(message, cause) {
        override val isRetryable: Boolean get() = retryable || requiresUserAuthentication
    }

    /**
     * The OS permission named by [permission] must be granted before this can succeed. Not a
     * failure: the UI is expected to ask the user and then repeat the operation.
     */
    class PermissionRequiredException(
        val permission: String,
        message: String = "Permission required: $permission",
    ) : VisionStudioException(message)

    /** Local file, MediaStore, or bitmap pipeline failure. */
    class StorageException(
        message: String,
        cause: Throwable? = null,
    ) : VisionStudioException(message, cause)

    /** Anything not mapped to a more specific type. */
    class UnknownException(
        message: String = "Unexpected error",
        cause: Throwable? = null,
    ) : VisionStudioException(message, cause)
}

/** Maps arbitrary throwables onto the domain hierarchy. */
fun Throwable.toVisionStudioException(): VisionStudioException = when (this) {
    is VisionStudioException -> this
    is SocketTimeoutException -> VisionStudioException.TimeoutException(cause = this)
    is java.util.concurrent.TimeoutException -> VisionStudioException.TimeoutException(cause = this)
    is UnknownHostException -> VisionStudioException.NetworkException("Unable to resolve host", this)
    is IOException -> VisionStudioException.NetworkException(message ?: "I/O failure", this)
    is IllegalArgumentException -> VisionStudioException.ValidationException(null, message ?: "Invalid input")
    else -> VisionStudioException.UnknownException(message ?: javaClass.simpleName, this)
}
