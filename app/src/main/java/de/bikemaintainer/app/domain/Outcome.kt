package de.bikemaintainer.app.domain

import java.time.Instant

sealed interface Outcome<out T> {
    /**
     * [asOf] is set when the server could not be reached and [value] is the answer stored offline: the time it was
     * fetched. `null` means the value is fresh from the server.
     */
    data class Success<out T>(val value: T, val asOf: Instant? = null) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

/** The older of two offline times: data combined from several answers is as old as its oldest part. */
fun olderOf(a: Instant?, b: Instant?): Instant? = when {
    a == null -> b
    b == null -> a
    else -> minOf(a, b)
}

sealed interface AppError {
    /** No connection, timeout, TLS problem. */
    data object Network : AppError

    /** Plain HTTP to an address outside the home network was refused. */
    data object CleartextNotAllowed : AppError

    /** The server address typed by the user is not a valid http(s) URL. */
    data object InvalidAddress : AppError

    /** No server is stored (yet, or any more after logout/switch server): set up a server first. */
    data object NotConfigured : AppError

    /** Local storage (settings, encrypted token) could not be read or written. */
    data object Storage : AppError

    /**
     * The server answered 401, even after a renewal attempt. UI navigation must follow
     * [de.bikemaintainer.app.data.network.SessionState.status], not this error alone: a transient renewal failure
     * (network, Keystore) also yields Unauthorized while the stored token or profile stays valid.
     */
    data object Unauthorized : AppError

    data object NotFound : AppError

    /**
     * Any other failure. [code] is `ErrorResponse.code` (e.g. `EMAIL_NOT_VERIFIED`, `ACCOUNT_DISABLED`), if the
     * server sent one. Status -1 means a successful response whose body could not be read.
     */
    data class Http(val status: Int, val code: String?) : AppError
}
