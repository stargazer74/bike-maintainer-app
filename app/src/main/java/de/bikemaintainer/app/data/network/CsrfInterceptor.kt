package de.bikemaintainer.app.data.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Copies the XSRF-TOKEN cookie into the X-XSRF-TOKEN header for state-changing requests.
 *
 * Must be registered as a network interceptor: after a session renewal the authenticator's follow-up request skips
 * application interceptors, and the cookie (and thus the token) changes with the new session. Here it is read fresh.
 */
class CsrfInterceptor(private val cookieJar: InMemoryCookieJar) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method !in STATE_CHANGING) return chain.proceed(request)
        val token = cookieJar.valueOf(request.url, COOKIE_NAME) ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(HEADER_NAME, token).build())
    }

    private companion object {
        const val COOKIE_NAME = "XSRF-TOKEN"
        const val HEADER_NAME = "X-XSRF-TOKEN"
        val STATE_CHANGING = setOf("POST", "PUT", "PATCH", "DELETE")
    }
}
