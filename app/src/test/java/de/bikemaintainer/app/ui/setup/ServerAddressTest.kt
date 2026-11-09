package de.bikemaintainer.app.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    @Test
    fun addressIsNormalized() {
        assertEquals("https://app.bike-maintainer.de/", ServerAddress.normalize(" app.bike-maintainer.de "))
        assertEquals("http://192.168.178.20:8080/", ServerAddress.normalize("HTTP://192.168.178.20:8080/"))
        assertEquals("https://h/", ServerAddress.normalize("https://h//"))
    }

    @Test
    fun invalidAddressIsNull() {
        listOf("", "   ", "http://", "ftp://x").forEach { assertNull(it, ServerAddress.normalize(it)) }
    }

    @Test
    fun cleartextIsDetected() {
        assertTrue(ServerAddress.isCleartext("http://192.168.178.20:8080/"))
        assertFalse(ServerAddress.isCleartext("https://app.bike-maintainer.de/"))
    }
}
