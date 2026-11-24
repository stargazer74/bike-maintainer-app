package de.bikemaintainer.app.features.support

import java.net.ServerSocket
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Test

/** The scenario backend itself: a taken port must not end the scenario run (app#100). */
class FakeBackendTest {
    @Test fun startsOnAnotherPortWhenTheFirstIsTaken() {
        ServerSocket(0).use { taken ->
            val free = ServerSocket(0).use { it.localPort }
            val candidates = ArrayDeque(listOf(taken.localPort, free))
            val backend = FakeBackend { candidates.removeFirst() }
            try {
                assertEquals(free, URI(backend.url).port)
            } finally {
                backend.close()
            }
        }
    }

    @Test fun reachableAgainServesOnTheSamePort() {
        val backend = FakeBackend()
        try {
            val port = URI(backend.url).port
            backend.reachable = false
            backend.reachable = true
            assertEquals(port, backend.server.port)
        } finally {
            backend.close()
        }
    }
}
