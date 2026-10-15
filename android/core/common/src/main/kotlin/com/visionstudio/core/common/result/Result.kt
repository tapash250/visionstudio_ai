package com.visionstudio.core.common.result

import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.error.toVisionStudioException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlin.coroutines.cancellation.CancellationException

/**
 * Outcome of a data-layer operation. [Loading] lets streaming sources (SSE progress, polling
 * workers) report intermediate state through the same type the UI already consumes.
 */
sealed interface Result<out T> {
    data class Success<out T>(val data: T) : Result<T>

    data class Error(val exception: VisionStudioException) : Result<Nothing>

    /** [progress] is a fraction in 0..1, or null when indeterminate. */
    data class Loading(val progress: Float? = null) : Result<Nothing> {
        init {
            progress?.let { require(it in 0f..1f) { "progress must be in 0..1, was $it" } }
        }
    }
}

val Result<*>.isSuccess: Boolean get() = this is Result.Success
val Result<*>.isError: Boolean get() = this is Result.Error
val Result<*>.isLoading: Boolean get() = this is Result.Loading

fun <T> Result<T>.getOrNull(): T? = (this as? Result.Success)?.data

fun <T> Result<T>.exceptionOrNull(): VisionStudioException? = (this as? Result.Error)?.exception

fun <T> Result<T>.getOrThrow(): T = when (this) {
    is Result.Success -> data
    is Result.Error -> throw exception
    is Result.Loading -> throw IllegalStateException("Result is still loading")
}

inline fun <T> Result<T>.getOrElse(fallback: (Result<T>) -> T): T = when (this) {
    is Result.Success -> data
    else -> fallback(this)
}

inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.Success(transform(data))
    is Result.Error -> this
    is Result.Loading -> this
}

inline fun <T, R> Result<T>.flatMap(transform: (T) -> Result<R>): Result<R> = when (this) {
    is Result.Success -> transform(data)
    is Result.Error -> this
    is Result.Loading -> this
}

inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T> {
    if (this is Result.Success) action(data)
    return this
}

inline fun <T> Result<T>.onError(action: (VisionStudioException) -> Unit): Result<T> {
    if (this is Result.Error) action(exception)
    return this
}

inline fun <T> Result<T>.onLoading(action: (Float?) -> Unit): Result<T> {
    if (this is Result.Loading) action(progress)
    return this
}

/**
 * Runs [block] and captures any failure as [Result.Error]. [CancellationException] is always
 * rethrown so structured concurrency keeps working.
 */
suspend inline fun <T> suspendRunCatching(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.Error(throwable.toVisionStudioException())
    }

/** Wraps a cold flow so collectors receive Loading → Success* and terminal errors as values. */
fun <T> Flow<T>.asResult(): Flow<Result<T>> =
    map<T, Result<T>> { Result.Success(it) }
        .onStart { emit(Result.Loading()) }
        .catch { throwable ->
            if (throwable is CancellationException) throw throwable
            emit(Result.Error(throwable.toVisionStudioException()))
        }
