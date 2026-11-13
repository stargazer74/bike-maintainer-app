package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.infrastructure.Serializer
import de.bikemaintainer.app.data.repository.RepositoryTestEnv.Companion.json
import de.bikemaintainer.app.data.storage.FileOutbox
import de.bikemaintainer.app.data.storage.OutboxEntry
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.PendingProblem
import de.bikemaintainer.app.domain.PendingState
import de.bikemaintainer.app.domain.Recorded
import de.bikemaintainer.app.domain.ServerMode
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A server before 0.20.0 has no `PUT …/mileage` (app#72): the mileage must still be saved, never silently lost. */
class OlderServerMileageTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var env: RepositoryTestEnv
    private var vehicleExists = true

    @After fun tearDown() = env.close()

    private fun vehicle(mileage: Int) = """{"id":3,"name":"Tenere","type":"MOTORCYCLE","make":"Yamaha","model":"XT",
        "modelYear":2021,"firstRegistrationDate":"2021-05-04","currentMileage":$mileage,
        "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}"""

    private fun setUp(serverMileage: Int = 24000, missingStatus: Int = 404) {
        env = RepositoryTestEnv(tmp.newFile("s.preferences_pb")) { req ->
            val path = req.url.encodedPath
            when {
                // The older server does not know the endpoint.
                path.endsWith("/mileage") -> json("""{"status":$missingStatus}""", missingStatus)
                !vehicleExists -> json("{}", 404)
                req.method == "GET" && path == "/api/v1/vehicles/3" -> json(vehicle(serverMileage))
                req.method == "PUT" && path == "/api/v1/vehicles/3" -> {
                    val sent = Json.parseToJsonElement(req.body!!.utf8()).jsonObject["currentMileage"]!!.jsonPrimitive.content
                    json(vehicle(sent.toInt()))
                }
                else -> json("{}", 500)
            }
        }
        runBlocking { env.settings.setServer(env.url, ServerMode.PORTAL) }
    }

    private fun repo() = ApiVehicleRepository(env.provider)

    @Test
    fun theMileageIsSavedTheOldWayKeepingTheOtherFields() {
        setUp()
        assertEquals(Outcome.Success(Recorded.SAVED), runBlocking { repo().raiseMileage(3, 24500) })
        val put = env.requests.last()
        assertEquals("PUT", put.method)
        assertEquals("/api/v1/vehicles/3", put.url.encodedPath)
        val body = Json.parseToJsonElement(put.body!!.utf8()).jsonObject
        assertEquals("24500", body["currentMileage"]!!.jsonPrimitive.content)
        assertEquals("Tenere", body["name"]!!.jsonPrimitive.content)
        assertEquals("Yamaha", body["make"]!!.jsonPrimitive.content)
        assertEquals("2021-05-04", body["firstRegistrationDate"]!!.jsonPrimitive.content)
    }

    @Test
    fun alsoWhenTheServerAnswers405() {
        setUp(missingStatus = 405)
        assertEquals(Outcome.Success(Recorded.SAVED), runBlocking { repo().raiseMileage(3, 24500) })
    }

    @Test
    fun aLowerValueIsRefusedAsByTheNewServer() {
        setUp(serverMileage = 25000)
        assertEquals(Outcome.Failure(AppError.Http(409, "MILEAGE_BELOW_CURRENT")), runBlocking { repo().raiseMileage(3, 24500) })
        assertTrue(env.requests.none { it.method == "PUT" && it.url.encodedPath == "/api/v1/vehicles/3" })
    }

    @Test
    fun theSameValueChangesNothing() {
        setUp(serverMileage = 24500)
        assertEquals(Outcome.Success(Recorded.SAVED), runBlocking { repo().raiseMileage(3, 24500) })
        assertTrue(env.requests.none { it.method == "PUT" && it.url.encodedPath == "/api/v1/vehicles/3" })
    }

    @Test
    fun onlyAMissingVehicleIsReportedAsGone() {
        setUp()
        vehicleExists = false
        assertEquals(Outcome.Failure(AppError.NotFound), runBlocking { repo().raiseMileage(3, 24500) })
    }

    @Test
    fun queuedMileageIsSentToTheOlderServerToo() {
        setUp()
        val outbox = FileOutbox(File(tmp.root, "outbox/entries.json"), Serializer.kotlinxSerializationJson)
        val pending = PendingRepository(outbox)
        runBlocking {
            pending.enqueue(OutboxEntry("m1", 3, 0, mileage = 24500))
            pending.enqueue(OutboxEntry("m2", 3, 0, mileage = 100))
        }
        assertEquals(SyncResult.DONE, runBlocking { OutboxSync(env.provider, outbox).run() })
        // The first went through the old way; the second is below the server's value and waits to be discarded.
        assertEquals(
            listOf(PendingState.Failed(PendingProblem.MILEAGE_BELOW_CURRENT)),
            runBlocking { pending.items.first() }.map { it.state },
        )
        assertTrue(env.requests.any { it.method == "PUT" && it.url.encodedPath == "/api/v1/vehicles/3" })
    }
}
