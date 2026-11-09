package de.bikemaintainer.app.data.network

import okhttp3.Interceptor
import okhttp3.Response

/** Debug log: method, path and status only. Never query, headers or body (tokens, personal data). */
class RequestLogInterceptor(private val log: (String) -> Unit) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = try {
            chain.proceed(request)
        } catch (e: java.io.IOException) {
            log("${request.method} ${request.url.encodedPath} -> ${e.javaClass.simpleName}")
            throw e
        }
        log("${request.method} ${request.url.encodedPath} -> ${response.code}")
        return response
    }
}
