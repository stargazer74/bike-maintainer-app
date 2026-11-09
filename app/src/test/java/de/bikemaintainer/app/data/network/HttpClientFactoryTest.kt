package de.bikemaintainer.app.data.network

import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Authenticator
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpClientFactoryTest {
    private val none = Authenticator.NONE

    @Test
    fun timeoutsAre15And30Seconds() {
        val client = HttpClientFactory.create(InMemoryCookieJar(), none)
        assertEquals(TimeUnit.SECONDS.toMillis(15), client.connectTimeoutMillis.toLong())
        assertEquals(TimeUnit.SECONDS.toMillis(30), client.readTimeoutMillis.toLong())
    }

    @Test
    fun networkInterceptorOrderIsGuardThenCsrf() {
        val client = HttpClientFactory.create(InMemoryCookieJar(), none)
        assertEquals(
            listOf(CleartextGuard::class, CsrfInterceptor::class),
            client.networkInterceptors.map { it::class },
        )
    }

    @Test
    fun noLoggingWithoutDebugLog() {
        val client = HttpClientFactory.create(InMemoryCookieJar(), none)
        assertTrue(client.interceptors.none { it is RequestLogInterceptor })
        assertTrue(client.networkInterceptors.none { it is RequestLogInterceptor })
    }

    @Test
    fun loggingWithDebugLog() {
        val lines = mutableListOf<String>()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse())
            val client = HttpClientFactory.create(InMemoryCookieJar(), none, debugLog = { lines += it })
            client.newCall(Request.Builder().url(server.url("/a")).build()).execute().close()
        }
        assertEquals(listOf("GET /a -> 200"), lines)
    }
}
