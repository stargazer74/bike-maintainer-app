package de.bikemaintainer.app.data.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Authenticator
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorIdInterceptorTest {
    private val state = ErrorIdState()

    private fun answer(status: Int, requestId: String? = REQUEST_ID): Int = MockWebServer().use { server ->
        server.start()
        val response = MockResponse.Builder().code(status)
        requestId?.let { response.addHeader("X-Request-Id", it) }
        server.enqueue(response.build())
        val client = HttpClientFactory.create(
            InMemoryCookieJar(),
            Authenticator.NONE,
            applicationInterceptors = listOf(ErrorIdInterceptor(state)),
        )
        client.newCall(Request.Builder().url(server.url("/api/v1/vehicles")).build()).execute().use { it.code }
    }

    @Test
    fun reportsTheFirst8CharactersOfAServerError() {
        assertEquals(500, answer(500))
        assertEquals("4bf92f35", state.errorId.value)
    }

    @Test
    fun alsoReportsErrorsOfNginxItself() {
        answer(502)
        assertEquals("4bf92f35", state.errorId.value)
    }

    @Test
    fun ignoresClientErrors() {
        answer(404)
        assertNull(state.errorId.value)
    }

    @Test
    fun ignoresServerErrorsWithoutRequestId() {
        answer(500, requestId = null)
        assertNull(state.errorId.value)
    }

    @Test
    fun keepsTheLastErrorIdUntilDismissed() {
        answer(500)
        answer(503, requestId = "abcdef0123456789abcdef0123456789")
        assertEquals("abcdef01", state.errorId.value)

        state.dismiss()

        assertNull(state.errorId.value)
    }

    @Test
    fun errorIdOfIgnoresBlankIds() {
        assertNull(ErrorIdInterceptor.errorIdOf(500, "  "))
        assertEquals("abc", ErrorIdInterceptor.errorIdOf(500, " abc "))
    }

    private companion object {
        const val REQUEST_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
    }
}
