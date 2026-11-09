package de.bikemaintainer.app.data.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryCookieJarTest {
    private val url = "http://192.168.1.2/api/v1/x".toHttpUrl()

    private fun cookie(value: String, expiresAt: Long = System.currentTimeMillis() + 60_000) =
        Cookie.Builder().name("XSRF-TOKEN").value(value).hostOnlyDomain(url.host).path("/")
            .expiresAt(expiresAt).build()

    @Test
    fun replacesSameCookie() {
        val jar = InMemoryCookieJar()
        jar.saveFromResponse(url, listOf(cookie("a")))
        jar.saveFromResponse(url, listOf(cookie("b")))
        assertEquals(1, jar.loadForRequest(url).size)
        assertEquals("b", jar.valueOf(url, "XSRF-TOKEN"))
    }

    @Test
    fun dropsExpired() {
        val jar = InMemoryCookieJar()
        jar.saveFromResponse(url, listOf(cookie("a")))
        jar.saveFromResponse(url, listOf(cookie("a", expiresAt = 1L)))
        assertNull(jar.valueOf(url, "XSRF-TOKEN"))
        assertEquals(0, jar.loadForRequest(url).size)
    }

    @Test
    fun clearRemovesAll() {
        val jar = InMemoryCookieJar()
        jar.saveFromResponse(url, listOf(cookie("a")))
        jar.clear()
        assertNull(jar.valueOf(url, "XSRF-TOKEN"))
    }

    @Test
    fun otherHostDoesNotMatch() {
        val jar = InMemoryCookieJar()
        jar.saveFromResponse(url, listOf(cookie("a")))
        assertNull(jar.valueOf("http://10.0.0.9/".toHttpUrl(), "XSRF-TOKEN"))
    }
}
