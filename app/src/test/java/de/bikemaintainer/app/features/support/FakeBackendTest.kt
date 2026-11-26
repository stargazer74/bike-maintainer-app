package de.bikemaintainer.app.features.support

import de.bikemaintainer.app.api.models.VehicleType
import java.net.ServerSocket
import java.net.URI
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun searchesTemplatesAppliesThemAndSkipsWhatTheVehicleHasFromThem() {
        val backend = FakeBackend()
        try {
            backend.addVehicleTemplate(VehicleType.MOTORCYCLE, "Honda", "CB 650 R", listOf("Ölwechsel", "Kette"))
            val vehicle = backend.addVehicle("Hornet", VehicleType.MOTORCYCLE, 100)
            val client = okhttp3.OkHttpClient()
            fun call(method: String, path: String, json: String? = null): String {
                val body = json?.toRequestBody("application/json".toMediaType())
                val request = okhttp3.Request.Builder().url(backend.url.trimEnd('/') + path).method(method, body).build()
                return client.newCall(request).execute().use { "${it.code} ${it.body.string()}" }
            }
            assertTrue(call("GET", "/api/v1/vehicle-templates?make=honda&type=MOTORCYCLE&model=cb650&year=2020").contains("honda-cb-650-r"))
            assertEquals("200 []", call("GET", "/api/v1/vehicle-templates?make=honda&type=CAR"))
            assertEquals("200 []", call("GET", "/api/v1/vehicle-templates?make=honda&year=2010"))
            assertTrue(call("GET", "/api/v1/vehicle-templates/honda-cb-650-r").startsWith("200 "))
            assertTrue(call("GET", "/api/v1/vehicle-templates/unknown").startsWith("404 "))
            val apply = """{"templateId":"honda-cb-650-r","tasks":[{"key":"ölwechsel","lastDoneKm":50}]}"""
            assertEquals("""200 {"created":1,"skipped":0}""", call("POST", "/api/v1/vehicles/${vehicle.id}/template", apply))
            assertEquals("""200 {"created":0,"skipped":1}""", call("POST", "/api/v1/vehicles/${vehicle.id}/template", apply))
            assertEquals(50, backend.task("Ölwechsel").lastServiceMileage)
            assertTrue(call("POST", "/api/v1/vehicles/${vehicle.id}/template", """{"templateId":"x","tasks":[]}""").startsWith("404 "))
            assertTrue(
                call("POST", "/api/v1/vehicles/${vehicle.id}/template", """{"templateId":"honda-cb-650-r","tasks":[{"key":"nope"}]}""")
                    .startsWith("400 "),
            )
            assertEquals(emptyList<String>(), backend.unknownRequests.toList())
        } finally {
            backend.close()
        }
    }
}
