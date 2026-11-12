package de.bikemaintainer.app.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerQrTest {
    @Test fun acceptsTheAddressOfAServer() {
        assertEquals("http://192.168.178.20:8080/", ServerQr.address("http://192.168.178.20:8080"))
        assertEquals("https://bm.example.org/", ServerQr.address(" HTTPS://bm.example.org/ \n"))
        assertEquals("http://raspberrypi.local:8080/", ServerQr.address("http://raspberrypi.local:8080/?x=1#top"))
    }

    @Test fun rejectsEverythingElse() {
        for (text in listOf(
            "",
            "bm.example.org",
            "Hallo Welt",
            "WIFI:S:Heimnetz;T:WPA;P:geheim;;",
            "ftp://bm.example.org",
            "javascript:alert(1)",
            "https://user:pass@bm.example.org",
            "https://bm.example.org/a b",
            // A worksheet QR code of the app is no server address.
            "BM1;v=1;vehicle=12;template=VALVE_CLEARANCE",
        )) {
            assertNull(text, ServerQr.address(text))
        }
    }
}
