package de.bikemaintainer.app.data.network

import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient

object HttpClientFactory {
    /**
     * CleartextGuard and CsrfInterceptor are network interceptors on purpose: they also apply to the authenticator's
     * follow-up request, which does not pass application interceptors.
     */
    fun create(
        cookieJar: InMemoryCookieJar,
        authenticator: Authenticator,
        applicationInterceptors: List<Interceptor> = emptyList(),
        debugLog: ((String) -> Unit)? = null,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .authenticator(authenticator)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // The API never redirects. A misconfigured proxy must not get the device token or a password sent to
            // another address (app#98); the session client is derived from this one and inherits it.
            .followRedirects(false)
            .followSslRedirects(false)
        applicationInterceptors.forEach(builder::addInterceptor)
        builder.addNetworkInterceptor(CleartextGuard())
        builder.addNetworkInterceptor(CsrfInterceptor(cookieJar))
        if (debugLog != null) builder.addNetworkInterceptor(RequestLogInterceptor(debugLog))
        return builder.build()
    }
}
