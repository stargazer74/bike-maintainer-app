package de.bikemaintainer.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import de.bikemaintainer.app.data.repository.RepositoryTestEnv.Companion.json
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleInput
import de.bikemaintainer.app.domain.VehicleSummary
import de.bikemaintainer.app.domain.VehicleType
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import kotlinx.coroutines.flow.flow
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

    private fun setUp(
        configured: Boolean = true,
        handler: (mockwebserver3.RecordedRequest) -> mockwebserver3.MockResponse,
    ): VehicleRepository {
        val e = RepositoryTestEnv(tmp.newFile("s.preferences_pb"), handler = handler)
        env = e
        if (configured) runBlocking { e.settings.setServer(e.url, de.bikemaintainer.app.domain.ServerMode.PORTAL) }
        return ApiVehicleRepository(e.provider)
    }

    private val response = """{"id":3,"name":"Tenere","type":"MOTORCYCLE","make":"Yamaha","model":"XT","modelYear":2021,
        "firstRegistrationDate":"2021-05-04","currentMileage":24000,
        "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}"""

    @Test
    fun createVehiclePostsTheFieldsAndLeavesOutBlankOptionals() {
        val repo = setUp { json(response, 201) }
        val input = VehicleInput("Tenere", VehicleType.MOTORCYCLE, "Yamaha", null, 2021, LocalDate.of(2021, 5, 4), 24000)
        val result = runBlocking { repo.createVehicle(input) }
        assertEquals(3L, (result as Outcome.Success).value.id)
        val request = env!!.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/vehicles", request.url.encodedPath)
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("Tenere", body.getValue("name").jsonPrimitive.content)
        assertEquals("MOTORCYCLE", body.getValue("type").jsonPrimitive.content)
        assertEquals("2021-05-04", body.getValue("firstRegistrationDate").jsonPrimitive.content)
        assertEquals("24000", body.getValue("currentMileage").jsonPrimitive.content)
        assertEquals(null, body["model"]?.takeUnless { it is kotlinx.serialization.json.JsonNull })
    }

    @Test
    fun updateVehiclePutsToItsAddress() {
        val repo = setUp { json(response) }
        val input = VehicleInput("Tenere", VehicleType.MOTORCYCLE, "Yamaha", "XT", 2021, LocalDate.of(2021, 5, 4), 24000)
        runBlocking { repo.updateVehicle(3, input) }
        val request = env!!.requests.single()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/vehicles/3", request.url.encodedPath)
    }

    @Test
    fun deleteVehicleAcceptsTheEmptyAnswer() {
        val repo = setUp { mockwebserver3.MockResponse.Builder().code(204).build() }
        assertEquals(Outcome.Success(Unit), runBlocking { repo.deleteVehicle(3) })
        val request = env!!.requests.single()
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/vehicles/3", request.url.encodedPath)
    }

    @Test
    fun refusedDeleteStaysAFailure() {
        val repo = setUp { json("""{"code":"X"}""", 500) }
        assertEquals(Outcome.Failure(AppError.Http(500, "X")), runBlocking { repo.deleteVehicle(3) })
    }

    @Test
    fun withoutStoredServerNotConfiguredIsReported() {
        val repo = setUp(configured = false) { json("[]") }
        val vehicle = Vehicle(3, "Tenere", VehicleType.MOTORCYCLE, "Yamaha", "XT", 2021, LocalDate.of(2021, 5, 4), 23000)
        val notConfigured = Outcome.Failure(AppError.NotConfigured)
        assertEquals(notConfigured, runBlocking { repo.vehicles() })
        assertEquals(notConfigured, runBlocking { repo.vehicle(3) })
        assertEquals(notConfigured, runBlocking { repo.updateMileage(vehicle, 24000) })
        assertEquals(0, env!!.requests.size)
    }

    @Test
    fun unreadableSettingsAreReportedAsStorage() {
        val e = RepositoryTestEnv(
            tmp.newFile("s.preferences_pb"),
            dataStoreWrap = { real ->
                object : DataStore<Preferences> {
                    override val data = flow<Preferences> {
                        throw IOException("unreadable")
                    }
                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                        real.updateData(transform)
                }
            },
            handler = { json("[]") },
        )
        env = e
        assertEquals(Outcome.Failure(AppError.Storage), runBlocking { ApiVehicleRepository(e.provider).vehicles() })
        assertEquals(0, e.requests.size)
    }

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

    private fun vehicleJson(id: Int) = """{"id":$id,"name":"V$id","type":"CAR","modelYear":2020,
        "firstRegistrationDate":"2020-01-01","currentMileage":1000,
        "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}"""

    private fun taskJson(id: Int, vehicleId: Int, status: String, active: Boolean = true) =
        """{"id":$id,"vehicleId":$vehicleId,"name":"T$id","oneTime":false,"active":$active,"status":"$status"}"""

    @Test
    fun summariesCombineVehiclesAndTasks() {
        val repo = setUp { req ->
            when (req.url.encodedPath) {
                "/api/v1/vehicles" -> json("[${vehicleJson(2)},${vehicleJson(1)}]")
                "/api/v1/vehicles/1/maintenance-tasks" -> json("[${taskJson(1, 1, "OK")}]")
                "/api/v1/vehicles/2/maintenance-tasks" ->
                    json("[${taskJson(2, 2, "OVERDUE")},${taskJson(3, 2, "DUE_SOON")},${taskJson(4, 2, "OVERDUE", false)}]")
                else -> json("{}", 404)
            }
        }
        val summaries = (runBlocking { repo.summaries() } as Outcome.Success).value
        assertEquals(listOf(2L, 1L), summaries.map { it.vehicle.id })
        assertEquals(listOf(TaskStatus.OVERDUE, TaskStatus.OK), summaries.map { it.status })
        assertEquals(listOf(2, 0), summaries.map { it.dueCount })
    }

    @Test
    fun summariesFailWhenOneTaskListFails() {
        val repo = setUp { req ->
            when (req.url.encodedPath) {
                "/api/v1/vehicles" -> json("[${vehicleJson(1)},${vehicleJson(2)}]")
                "/api/v1/vehicles/1/maintenance-tasks" -> json("[]")
                else -> json("""{"code":"BOOM","status":500}""", 500)
            }
        }
        assertEquals(Outcome.Failure(AppError.Http(500, "BOOM")), runBlocking { repo.summaries() })
    }

    @Test
    fun summariesReportTheFirstFailureInVehicleOrderNotTheFastest() {
        val secondAnswered = CountDownLatch(1)
        val repo = setUp { req ->
            when (req.url.encodedPath) {
                "/api/v1/vehicles" -> json("[${vehicleJson(1)},${vehicleJson(2)}]")
                "/api/v1/vehicles/1/maintenance-tasks" -> {
                    secondAnswered.await(5, TimeUnit.SECONDS) // vehicle 1 fails last
                    json("""{"code":"FIRST","status":500}""", 500)
                }
                else -> json("""{"code":"SECOND","status":502}""", 502).also { secondAnswered.countDown() }
            }
        }
        assertEquals(Outcome.Failure(AppError.Http(500, "FIRST")), runBlocking { repo.summaries() })
        assertEquals(0L, secondAnswered.count)
    }

    @Test
    fun summariesFailWhenVehiclesFail() {
        val repo = setUp(configured = false) { json("[]") }
        assertEquals(Outcome.Failure(AppError.NotConfigured), runBlocking { repo.summaries() })
    }

    @Test
    fun summariesOfNoVehiclesAreEmpty() {
        val repo = setUp { json("[]") }
        assertEquals(Outcome.Success(emptyList<VehicleSummary>()), runBlocking { repo.summaries() })
        assertEquals(1, env!!.requests.size)
    }
}
