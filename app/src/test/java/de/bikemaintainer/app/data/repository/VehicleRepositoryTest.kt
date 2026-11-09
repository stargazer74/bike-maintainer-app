package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.data.repository.RepositoryTestEnv.Companion.json
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleType
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VehicleRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private var env: RepositoryTestEnv? = null

    @After
    fun tearDown() {
        env?.close()
    }

    private fun setUp(handler: (mockwebserver3.RecordedRequest) -> mockwebserver3.MockResponse): VehicleRepository {
        val e = RepositoryTestEnv(tmp.newFile("s.preferences_pb"), handler = handler)
        env = e
        runBlocking { e.settings.setServer(e.url, de.bikemaintainer.app.domain.ServerMode.PORTAL) }
        return VehicleRepository(e.provider)
    }

    private val response = """{"id":3,"name":"Tenere","type":"MOTORCYCLE","make":"Yamaha","model":"XT","modelYear":2021,
        "firstRegistrationDate":"2021-05-04","currentMileage":24000,
        "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}"""

    @Test
    fun updateMileageKeepsOtherFields() {
        val repo = setUp { json(response) }
        val vehicle = Vehicle(3, "Tenere", VehicleType.MOTORCYCLE, "Yamaha", "XT", 2021, LocalDate.of(2021, 5, 4), 23000)
        val result = runBlocking { repo.updateMileage(vehicle, 24000) }
        assertEquals(24000, (result as Outcome.Success).value.currentMileage)
        val request = env!!.requests.single()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/vehicles/3", request.url.encodedPath)
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("Tenere", body["name"]!!.jsonPrimitive.content)
        assertEquals("MOTORCYCLE", body["type"]!!.jsonPrimitive.content)
        assertEquals("Yamaha", body["make"]!!.jsonPrimitive.content)
        assertEquals("XT", body["model"]!!.jsonPrimitive.content)
        assertEquals("2021", body["modelYear"]!!.jsonPrimitive.content)
        assertEquals("2021-05-04", body["firstRegistrationDate"]!!.jsonPrimitive.content)
        assertEquals("24000", body["currentMileage"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorCodeIsMapped() {
        val repo = setUp { json("""{"code":"EMAIL_NOT_VERIFIED","status":403}""", 403) }
        val result = runBlocking { repo.vehicles() }
        assertEquals(Outcome.Failure(AppError.Http(403, "EMAIL_NOT_VERIFIED")), result)
    }

    @Test
    fun statusMapping() {
        val repo = setUp { req ->
            when (req.url.encodedPath) {
                "/api/v1/vehicles/1" -> mockwebserver3.MockResponse.Builder().code(401).build()
                "/api/v1/vehicles/2" -> mockwebserver3.MockResponse.Builder().code(404).build()
                else -> json("not json{", 429)
            }
        }
        assertEquals(Outcome.Failure(AppError.Unauthorized), runBlocking { repo.vehicle(1) })
        assertEquals(Outcome.Failure(AppError.NotFound), runBlocking { repo.vehicle(2) })
        assertEquals(Outcome.Failure(AppError.Http(429, null)), runBlocking { repo.vehicle(3) })
    }

    @Test
    fun connectionFailureIsNetworkError() {
        val repo = setUp { json("[]") }
        env!!.server.close()
        assertEquals(Outcome.Failure(AppError.Network), runBlocking { repo.vehicles() })
    }

    @Test
    fun unreadableBodyIsHttpMinusOne() {
        val repo = setUp { json("""{"unexpected":true}""") }
        assertEquals(Outcome.Failure(AppError.Http(-1, null)), runBlocking { repo.vehicle(1) })
    }
}
