package de.bikemaintainer.app.data.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestLogInterceptorTest {
    @Test
    fun logsMethodPathStatusOnly() {
        val lines = mutableListOf<String>()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse())
            val client = OkHttpClient.Builder().addInterceptor(RequestLogInterceptor { lines += it }).build()
            val url = server.url("/api/v1/vehicles").newBuilder().addQueryParameter("q", "secret").build()
            client.newCall(Request.Builder().url(url).header("Authorization", "secret").build()).execute().close()
        }
        assertEquals(listOf("GET /api/v1/vehicles -> 200"), lines)
    }
}
