package de.bikemaintainer.app.data.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CleartextGuardTest {
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.close()
    }

    private fun clientWith(allowed: Boolean, tls: HandshakeCertificates? = null) =
        OkHttpClient.Builder().addNetworkInterceptor(CleartextGuard { allowed }).apply {
            if (tls != null) sslSocketFactory(tls.sslSocketFactory(), tls.trustManager)
        }.build()

    @Test
    fun httpToHomeAddressIsAllowed() {
        server.start()
        server.enqueue(MockResponse())
        val response = clientWith(true).newCall(Request.Builder().url(server.url("/")).build()).execute()
        assertEquals(200, response.code)
        response.close()
    }

    @Test
    fun httpToNonHomeAddressFailsBeforeSending() {
        server.start()
        server.enqueue(MockResponse())
        assertThrows(CleartextNotAllowedException::class.java) {
            clientWith(false).newCall(Request.Builder().url(server.url("/")).build()).execute()
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun httpsIsNotChecked() {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server.useHttps(serverTls.sslSocketFactory())
        server.start()
        server.enqueue(MockResponse())
        val response = clientWith(false, clientTls).newCall(Request.Builder().url(server.url("/")).build()).execute()
        assertEquals(200, response.code)
        response.close()
    }
}
