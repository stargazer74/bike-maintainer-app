package de.bikemaintainer.app.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Error ID of the last server error (5xx), shown above all screens until dismissed. Users can quote it in a report;
 * the logs of the server's nginx (`trace_id=…`) and backend (`traceId`) contain the full ID.
 */
class ErrorIdState {
    private val current = MutableStateFlow<String?>(null)

    val errorId: StateFlow<String?> = current.asStateFlow()

    fun report(id: String) {
        current.value = id
    }

    fun dismiss() {
        current.value = null
    }
}

/** Application interceptor: reports the error ID of every server error; the screens' own error texts stay. */
class ErrorIdInterceptor(private val state: ErrorIdState) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        errorIdOf(response.code, response.header(REQUEST_ID_HEADER))?.let(state::report)
        return response
    }

    companion object {
        /** Response header with the request's trace ID, set by the frontend's nginx for every /api/ request. */
        const val REQUEST_ID_HEADER = "X-Request-Id"

        /** Shown characters: short enough to read out, still unique within the 14 days of kept logs. */
        const val ERROR_ID_LENGTH = 8

        private const val HTTP_SERVER_ERROR = 500

        /** Error ID of a server error, or null for other answers or without the header (e.g. backend without nginx). */
        fun errorIdOf(status: Int, requestId: String?): String? =
            requestId?.trim()?.takeIf { status >= HTTP_SERVER_ERROR && it.isNotEmpty() }?.take(ERROR_ID_LENGTH)
    }
}
