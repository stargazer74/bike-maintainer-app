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

class VehicleRepositoryOfflineTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var env: RepositoryTestEnv
    private val fetched = java.time.Instant.parse("2026-11-13T20:40:00Z")

    @After
    fun tearDown() {
        env.close()
    }

    private fun vehicleJson(id: Int) = """{"id":$id,"name":"V$id","type":"CAR","modelYear":2020,
        "firstRegistrationDate":"2020-01-01","currentMileage":1000,
        "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}"""

    private fun taskJson(id: Int, vehicleId: Int, status: String) =
        """{"id":$id,"vehicleId":$vehicleId,"name":"T$id","oneTime":false,"active":true,"status":"$status"}"""

    private var vehicleIds = listOf(1, 2)

    private fun setUp(): Pair<ApiVehicleRepository, de.bikemaintainer.app.data.storage.FileOfflineStore> {
        env = RepositoryTestEnv(tmp.newFile("s.preferences_pb")) { req ->
            val path = req.url.encodedPath
            when {
                path == "/api/v1/vehicles" -> json(vehicleIds.joinToString(",", "[", "]") { vehicleJson(it) })
                path.endsWith("/maintenance-tasks") -> {
                    val id = path.split("/")[4].toInt()
                    json("[${taskJson(id * 10, id, "OVERDUE")}]")
                }
                path.matches(Regex("/api/v1/vehicles/\\d+")) -> {
                    val id = path.substringAfterLast("/").toInt()
                    if (id in vehicleIds) json(vehicleJson(id)) else json("{}", 404)
                }
                else -> json("{}", 404)
            }
        }
        runBlocking { env.settings.setServer(env.url, de.bikemaintainer.app.domain.ServerMode.PORTAL) }
        val offline = de.bikemaintainer.app.data.storage.FileOfflineStore(
            java.io.File(tmp.root, "offline"),
            de.bikemaintainer.app.api.infrastructure.Serializer.kotlinxSerializationJson,
            java.time.Clock.fixed(fetched, java.time.ZoneOffset.UTC),
        )
        return ApiVehicleRepository(env.provider, offline) to offline
    }

    @Test
    fun freshAnswersHaveNoOfflineTime() {
        val (repo, _) = setUp()
        val result = runBlocking { repo.summaries() } as Outcome.Success
        assertEquals(null, result.asOf)
    }

    @Test
    fun withoutConnectionTheLastListIsShownWithItsTime() {
        val (repo, _) = setUp()
        runBlocking { repo.summaries() }
        env.server.close() // no connection from here on
        val result = runBlocking { repo.summaries() } as Outcome.Success
        assertEquals(fetched, result.asOf)
        assertEquals(listOf(1L, 2L), result.value.map { it.vehicle.id })
        assertEquals(listOf(TaskStatus.OVERDUE, TaskStatus.OVERDUE), result.value.map { it.status })
    }

    @Test
    fun eachVehicleOfTheListOpensOffline() {
        val (repo, _) = setUp()
        runBlocking { repo.vehicles() }
        env.server.close() // no connection from here on
        val result = runBlocking { repo.vehicle(2) } as Outcome.Success
        assertEquals("V2", result.value.name)
        assertEquals(fetched, result.asOf)
    }

    @Test
    fun withoutStoredDataItStaysANetworkError() {
        val (repo, _) = setUp()
        env.server.close() // no connection from here on
        assertEquals(Outcome.Failure(AppError.Network), runBlocking { repo.summaries() })
    }

    @Test
    fun otherErrorsDoNotFallBack() {
        val (repo, _) = setUp()
        runBlocking { repo.vehicle(1) }
        vehicleIds = listOf(2)
        assertEquals(Outcome.Failure(AppError.NotFound), runBlocking { repo.vehicle(1) })
        env.server.close() // no connection from here on
        // The 404 dropped what was stored for it.
        assertEquals(Outcome.Failure(AppError.Network), runBlocking { repo.vehicle(1) })
    }

    @Test
    fun vehiclesGoneFromTheListAreForgotten() {
        val (repo, offline) = setUp()
        runBlocking { repo.summaries() }
        vehicleIds = listOf(2)
        runBlocking { repo.summaries() }
        val tasks = de.bikemaintainer.app.data.repository.TASKS
        assertEquals(null, runBlocking { offline.load(OfflineKeys.tasks(1), tasks) })
        assertEquals(null, runBlocking { offline.load(OfflineKeys.vehicle(1), de.bikemaintainer.app.api.models.VehicleResponse.serializer()) })
        assertEquals(1, runBlocking { offline.load(OfflineKeys.tasks(2), tasks) }?.value?.size)
    }

    @Test
    fun deletingAVehicleForgetsItsData() {
        val (repo, offline) = setUp()
        runBlocking { repo.summaries() }
        runBlocking { repo.deleteVehicle(1) } // answered 404 by the test server: already gone counts too
        assertEquals(null, runBlocking { offline.load(OfflineKeys.tasks(1), de.bikemaintainer.app.data.repository.TASKS) })
    }
}
