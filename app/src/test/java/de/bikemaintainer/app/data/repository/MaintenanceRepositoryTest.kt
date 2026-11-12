package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.data.repository.RepositoryTestEnv.Companion.json
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskInput
import de.bikemaintainer.app.domain.ServerMode
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MaintenanceRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val LOG = """{"id":9,"vehicleId":3,"performedAt":"2026-11-09","mileageAtPerformed":24100,"materials":[],
        "currency":"EUR","createdAt":"2026-11-09T10:00:00Z"}"""
    private lateinit var env: RepositoryTestEnv

    @After
    fun tearDown() {
        env.close()
    }

    private fun setUp(
        configured: Boolean = true,
        response: () -> MockResponse = { json(LOG, 201) },
    ): MaintenanceRepository {
        env = RepositoryTestEnv(tmp.newFile("s.preferences_pb"), handler = { response() })
        if (configured) runBlocking { env.settings.setServer(env.url, ServerMode.PORTAL) }
        return ApiMaintenanceRepository(env.provider)
    }

    private fun send(notes: String?) = runBlocking {
        setUp().logMaintenance(3, LocalDate.of(2026, 11, 9), 24100, setOf(1L, 2L), notes)
    }

    private fun body() = Json.parseToJsonElement(env.requests.single().body!!.utf8()).jsonObject

    private val TASK = """{"id":5,"vehicleId":3,"name":"Ventile","description":"alle 4","intervalKm":24000,
        "firstDueKm":12000,"oneTime":false,"active":true,"status":"OK"}"""

    @Test
    fun taskCarriesItsIntervals() {
        val repo = setUp(response = { json(TASK) })
        val task = (runBlocking { repo.task(3, 5) } as Outcome.Success).value
        assertEquals("/api/v1/vehicles/3/maintenance-tasks/5", env.requests.single().url.encodedPath)
        assertEquals("alle 4", task.description)
        assertEquals(24000, task.intervalKm)
        assertEquals(null, task.intervalMonths)
        assertEquals(12000, task.firstDueKm)
    }

    @Test
    fun createTaskPostsTheFields() {
        val repo = setUp(response = { json(TASK, 201) })
        val input = TaskInput("Ventile", null, 24000, 24, null, null, oneTime = false, active = true)
        assertEquals(5L, (runBlocking { repo.createTask(3, input) } as Outcome.Success).value.id)
        val request = env.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/vehicles/3/maintenance-tasks", request.url.encodedPath)
        val body = body()
        assertEquals("Ventile", body.getValue("name").jsonPrimitive.content)
        assertEquals("24000", body.getValue("intervalKm").jsonPrimitive.content)
        assertEquals("24", body.getValue("intervalMonths").jsonPrimitive.content)
    }

    @Test
    fun switchingOffSendsActiveFalse() {
        val repo = setUp(response = { json(TASK) })
        val input = TaskInput("Ventile", "alle 4", 24000, null, 12000, null, oneTime = false, active = false)
        runBlocking { repo.updateTask(3, 5, input) }
        val request = env.requests.single()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/vehicles/3/maintenance-tasks/5", request.url.encodedPath)
        assertEquals("false", body().getValue("active").jsonPrimitive.content)
        assertEquals("alle 4", body().getValue("description").jsonPrimitive.content)
    }

    @Test
    fun withoutStoredServerNotConfiguredIsReported() {
        val repo = setUp(configured = false)
        val notConfigured = Outcome.Failure(AppError.NotConfigured)
        assertEquals(notConfigured, runBlocking { repo.tasks(3) })
        assertEquals(notConfigured, runBlocking { repo.logMaintenance(3, LocalDate.of(2026, 11, 9), 1, setOf(1L), null) })
        assertEquals(0, env.requests.size)
    }

    @Test
    fun logMaintenanceSendsPerformedTaskIds() {
        assertEquals(Outcome.Success(Unit), send("Oil changed"))
        val request = env.requests.single()
        assertEquals("/api/v1/vehicles/3/maintenance-logs", request.url.encodedPath)
        val body = body()
        assertEquals("2026-11-09", body["performedAt"]!!.jsonPrimitive.content)
        assertEquals("24100", body["mileageAtPerformed"]!!.jsonPrimitive.content)
        assertEquals(listOf("1", "2"), body["performedTaskIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Oil changed", body["notes"]!!.jsonPrimitive.content)
    }

    @Test
    fun blankNotesAreOmitted() {
        send("  ")
        assertTrue(body()["notes"] == null || body()["notes"] == JsonNull)
    }

    @Test
    fun unreadableCreatedResponseCountsAsSaved() {
        val repo = setUp(response = { json("{", 201) })
        val result = runBlocking { repo.logMaintenance(3, LocalDate.of(2026, 11, 9), 24100, setOf(1L), null) }
        assertEquals(Outcome.Success(Unit), result)
        assertEquals(1, env.requests.size)
    }

    @Test
    fun logMaintenanceServerErrorStaysFailure() {
        val repo = setUp(response = { json("{}", 500) })
        val result = runBlocking { repo.logMaintenance(3, LocalDate.of(2026, 11, 9), 24100, setOf(1L), null) }
        assertEquals(Outcome.Failure(AppError.Http(500, null)), result)
    }
}
