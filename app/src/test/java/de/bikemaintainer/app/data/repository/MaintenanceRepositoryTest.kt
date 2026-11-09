package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.data.repository.RepositoryTestEnv.Companion.json
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.ServerMode
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private fun setUp(configured: Boolean = true): MaintenanceRepository {
        env = RepositoryTestEnv(tmp.newFile("s.preferences_pb"), handler = { json(LOG, 201) })
        if (configured) runBlocking { env.settings.setServer(env.url, ServerMode.PORTAL) }
        return MaintenanceRepository(env.provider)
    }

    private fun send(notes: String?) = runBlocking {
        setUp().logMaintenance(3, LocalDate.of(2026, 11, 9), 24100, setOf(1L, 2L), notes)
    }

    private fun body() = Json.parseToJsonElement(env.requests.single().body!!.utf8()).jsonObject

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
}
