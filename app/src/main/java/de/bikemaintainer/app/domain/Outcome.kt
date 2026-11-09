package de.bikemaintainer.app.domain

sealed interface Outcome<out T> {
    data class Success<out T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

sealed interface AppError {
    /** No connection, timeout, TLS problem. */
    data object Network : AppError

    /** Plain HTTP to an address outside the home network was refused. */
    data object CleartextNotAllowed : AppError

    data object Unauthorized : AppError

    data object NotFound : AppError

    /**
     * Any other failure. [code] is `ErrorResponse.code` (e.g. `EMAIL_NOT_VERIFIED`, `ACCOUNT_DISABLED`), if the
     * server sent one. Status -1 means a successful response whose body could not be read.
     */
    data class Http(val status: Int, val code: String?) : AppError
}
