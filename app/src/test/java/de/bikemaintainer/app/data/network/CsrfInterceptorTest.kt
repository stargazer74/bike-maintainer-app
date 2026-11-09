package de.bikemaintainer.app.data.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CsrfInterceptorTest {
    private val server = MockWebServer()
    private val jar = InMemoryCookieJar()
    private val client = OkHttpClient.Builder().cookieJar(jar).addNetworkInterceptor(CsrfInterceptor(jar)).build()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun primeToken() {
        server.enqueue(MockResponse.Builder().addHeader("Set-Cookie", "XSRF-TOKEN=abc; Path=/").build())
        client.newCall(Request.Builder().url(server.url("/api/v1/config")).build()).execute().close()
        server.takeRequest()
    }

    @Test
    fun stateChangingRequestCarriesXsrfHeader() {
        primeToken()
        server.enqueue(MockResponse())
        client.newCall(Request.Builder().url(server.url("/api/v1/x")).post("{}".toRequestBody()).build())
            .execute().close()
        assertEquals("abc", server.takeRequest().headers["X-XSRF-TOKEN"])
    }

    @Test
    fun getCarriesNoXsrfHeader() {
        primeToken()
        server.enqueue(MockResponse())
        client.newCall(Request.Builder().url(server.url("/api/v1/x")).build()).execute().close()
        assertNull(server.takeRequest().headers["X-XSRF-TOKEN"])
    }
}
