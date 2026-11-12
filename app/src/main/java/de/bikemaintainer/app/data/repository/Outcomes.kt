package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.data.network.CleartextNotAllowedException
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Response

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_NOT_FOUND = 404

/**
 * Like [call], on the APIs of the stored server: [AppError.NotConfigured] if none is stored, [AppError.Storage] if the
 * settings cannot be read.
 */
internal suspend fun <T : Any> ApiProvider.callStored(block: suspend (ApiProvider.Apis) -> Response<T>): Outcome<T> {
    val apis = try {
        apis()
    } catch (_: IOException) {
        return Outcome.Failure(AppError.Storage)
    } ?: return Outcome.Failure(AppError.NotConfigured)
    return call { block(apis) }
}

/** Runs one API call and maps every outcome, including transport errors, to an [Outcome]. */
internal suspend fun <T : Any> call(block: suspend () -> Response<T>): Outcome<T> {
    val response = try {
        block()
    } catch (_: CleartextNotAllowedException) { // before IOException, which it is a subclass of
        return Outcome.Failure(AppError.CleartextNotAllowed)
    } catch (_: IOException) {
        return Outcome.Failure(AppError.Network)
    } catch (_: SerializationException) {
        // 2xx whose body does not match the spec; there is no better status to report than a marker value.
        return Outcome.Failure(AppError.Http(-1, null))
    }
    if (response.isSuccessful) {
        val body = response.body() ?: return Outcome.Failure(AppError.Http(response.code(), null))
        return Outcome.Success(body)
    }
    return Outcome.Failure(
        when (response.code()) {
            HTTP_UNAUTHORIZED -> AppError.Unauthorized
            HTTP_NOT_FOUND -> AppError.NotFound
            else -> AppError.Http(response.code(), errorCode(response))
        },
    )
}

/** Like [call], for endpoints that answer without a body (202/204): Retrofit hands over no body for a 204. */
internal suspend fun callNoBody(block: suspend () -> Response<Unit>): Outcome<Unit> = when (val result = call(block)) {
    is Outcome.Failure -> {
        val error = result.error
        if (error is AppError.Http && error.status in HTTP_SUCCESS) Outcome.Success(Unit) else result
    }
    is Outcome.Success -> result
}

private val HTTP_SUCCESS = 200..299

private fun errorCode(response: Response<*>): String? = try {
    val text = response.errorBody()?.string().orEmpty()
    Json.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.contentOrNull
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) { // JSON that is not an object / not a primitive
    null
} catch (_: IOException) {
    null
}

internal inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}
