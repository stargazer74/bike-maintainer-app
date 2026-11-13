package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.infrastructure.Serializer
import de.bikemaintainer.app.api.models.MaintenanceLogRequest
import de.bikemaintainer.app.data.repository.RepositoryTestEnv.Companion.json
import de.bikemaintainer.app.data.storage.FileOutbox
import de.bikemaintainer.app.data.storage.OutboxEntry
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.PendingItem
import de.bikemaintainer.app.domain.PendingProblem
import de.bikemaintainer.app.domain.PendingState
import de.bikemaintainer.app.domain.Recorded
import de.bikemaintainer.app.domain.ServerMode
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutboxSyncTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var env: RepositoryTestEnv
    private lateinit var outbox: FileOutbox
    private var scheduled = 0
    private val entryId = "5b2c3f8e-1d4a-4c1e-9f00-00000000a001"

    /** Logs the server already has; POSTs are answered with 201 and recorded. */
    private var serverLogs = "[]"
    private var answer: (RecordedRequest) -> MockResponse? = { null }

    @After fun tearDown() = env.close()

    private fun setUp(): Pair<OutboxSync, PendingRepository> {
        env = RepositoryTestEnv(tmp.newFile("s.preferences_pb")) { req ->
            answer(req) ?: when {
                req.method == "GET" && req.url.encodedPath.endsWith("/maintenance-logs") -> json(serverLogs)
                req.method == "POST" -> json(logJson(entryId, "2026-11-09", "[1]"), 201)
                req.url.encodedPath.endsWith("/mileage") -> json(VEHICLE)
                else -> json("{}", 404)
            }
        }
        runBlocking { env.settings.setServer(env.url, ServerMode.PORTAL) }
        outbox = FileOutbox(File(tmp.root, "outbox/entries.json"), Serializer.kotlinxSerializationJson)
        return OutboxSync(env.provider, outbox) to PendingRepository(outbox) { scheduled++ }
    }

    private fun logJson(id: String?, date: String, tasks: String) =
        """{"id":9,"vehicleId":3,"performedAt":"$date","mileageAtPerformed":24100,"materials":[],"currency":"EUR",
        "createdAt":"2026-11-09T10:00:00Z","performedTaskIds":$tasks${if (id != null) ",\"clientEntryId\":\"$id\"" else ""}}"""

    private fun logEntry(raise: Int? = null) = OutboxEntry(
        id = entryId,
        vehicleId = 3,
        createdAt = 0,
        log = MaintenanceLogRequest(
            performedAt = LocalDate.of(2026, 11, 9),
            mileageAtPerformed = 24100,
            clientEntryId = UUID.fromString(entryId),
            performedTaskIds = setOf(1L),
        ),
        raiseMileageTo = raise,
    )

    private fun requests(method: String) = env.requests.filter { it.method == method }

    private fun items(pending: PendingRepository) = runBlocking { pending.items.first() }

    @Test
    fun mileageIsSentAndRemoved() {
        val (sync, pending) = setUp()
        runBlocking { pending.enqueue(OutboxEntry("m1", 3, 0, mileage = 24500)) }
        assertEquals(1, scheduled)
        assertEquals(SyncResult.DONE, runBlocking { sync.run() })
        val put = requests("PUT").single()
        assertEquals("/api/v1/vehicles/3/mileage", put.url.encodedPath)
        assertEquals("24500", Json.parseToJsonElement(put.body!!.utf8()).jsonObject["mileage"]!!.jsonPrimitive.content)
        assertTrue(items(pending).isEmpty())
    }

    @Test
    fun logIsSentWithItsIdAndTheMileageRaisedAfterIt() {
        val (sync, pending) = setUp()
        runBlocking { pending.enqueue(logEntry(raise = 24100)) }
        assertEquals(SyncResult.DONE, runBlocking { sync.run() })
        val post = requests("POST").single()
        assertEquals(entryId, Json.parseToJsonElement(post.body!!.utf8()).jsonObject["clientEntryId"]!!.jsonPrimitive.content)
        assertEquals("/api/v1/vehicles/3/mileage", requests("PUT").single().url.encodedPath)
        assertTrue(items(pending).isEmpty())
    }

    @Test
    fun logTheServerAlreadyHasIsNotSentAgain() {
        val (sync, pending) = setUp()
        serverLogs = "[${logJson(entryId, "2026-11-09", "[1]")}]"
        runBlocking { pending.enqueue(logEntry()) }
        assertEquals(SyncResult.DONE, runBlocking { sync.run() })
        assertTrue(requests("POST").isEmpty())
        assertTrue(items(pending).isEmpty())
    }

    @Test
    fun possibleDuplicateWaitsForTheUserAndIsSentAfterConfirming() {
        val (sync, pending) = setUp()
        serverLogs = "[${logJson(null, "2026-11-09", "[1]")}]"
        runBlocking { pending.enqueue(logEntry()) }
        assertEquals(SyncResult.DONE, runBlocking { sync.run() })
        assertTrue(requests("POST").isEmpty())
        assertEquals(PendingState.PossibleDuplicate(LocalDate.of(2026, 11, 9)), items(pending).single().state)

        runBlocking { pending.confirm(entryId) }
        assertEquals(SyncResult.DONE, runBlocking { sync.run() })
        assertEquals(1, requests("POST").size)
        assertTrue(items(pending).isEmpty())
    }

    @Test
    fun sameDayWithOtherTasksIsNoDuplicate() {
        val (sync, pending) = setUp()
        serverLogs = "[${logJson(null, "2026-11-09", "[2]")}]"
        runBlocking { pending.enqueue(logEntry()) }
        runBlocking { sync.run() }
        assertEquals(1, requests("POST").size)
    }

    @Test
    fun withoutConnectionEverythingStaysForLater() {
        val (sync, pending) = setUp()
        runBlocking { pending.enqueue(logEntry()) }
        env.server.close()
        assertEquals(SyncResult.RETRY, runBlocking { sync.run() })
        assertEquals(PendingState.Waiting, items(pending).single().state)
    }

    @Test
    fun deletedVehicleAndRejectedEntriesAreMarkedNotDropped() {
        val (sync, pending) = setUp()
        answer = { req ->
            when {
                req.url.encodedPath.startsWith("/api/v1/vehicles/4/") -> json("{}", 404)
                req.method == "POST" -> json("""{"status":400}""", 400)
                else -> null
            }
        }
        runBlocking {
            pending.enqueue(OutboxEntry("gone", 4, 0, mileage = 100))
            pending.enqueue(logEntry())
        }
        assertEquals(SyncResult.DONE, runBlocking { sync.run() })
        assertEquals(
            listOf(
                PendingState.Failed(PendingProblem.VEHICLE_GONE),
                PendingState.Failed(PendingProblem.REJECTED),
            ),
            items(pending).map { it.state },
        )
    }

    @Test
    fun aMileageBelowTheServersIsMarked() {
        val (sync, pending) = setUp()
        answer = { req -> if (req.method == "PUT") json("""{"code":"MILEAGE_BELOW_CURRENT"}""", 409) else null }
        runBlocking { pending.enqueue(OutboxEntry("m1", 3, 0, mileage = 1)) }
        runBlocking { sync.run() }
        assertEquals(PendingState.Failed(PendingProblem.MILEAGE_BELOW_CURRENT), items(pending).single().state)
    }

    @Test
    fun aRaiseThatFailsAfterTheLogContinuesAsAMileage() {
        val (sync, pending) = setUp()
        answer = { req -> if (req.method == "PUT") json("{}", 503) else null }
        runBlocking { pending.enqueue(logEntry(raise = 24100)) }
        assertEquals(SyncResult.RETRY, runBlocking { sync.run() })
        val left = items(pending).single()
        assertEquals(PendingItem.Kind.MILEAGE, left.kind)
        assertEquals(24100, left.mileage)
        answer = { null }
        assertEquals(SyncResult.DONE, runBlocking { sync.run() })
        assertEquals(1, requests("POST").size) // the log was not sent twice
    }

    @Test
    fun notSignedInStops() {
        val (sync, pending) = setUp()
        answer = { json("{}", 401) }
        runBlocking { pending.enqueue(OutboxEntry("m1", 3, 0, mileage = 24500)) }
        assertEquals(SyncResult.STOP, runBlocking { sync.run() })
        assertEquals(1, items(pending).size)
    }

    @Test
    fun repositoriesQueueWhatCannotBeSent() {
        val (_, pending) = setUp()
        val vehicles = ApiVehicleRepository(env.provider, pending = pending)
        val maintenance = ApiMaintenanceRepository(env.provider, pending = pending)
        env.server.close()
        val id = UUID.fromString(entryId)
        assertEquals(Outcome.Success(Recorded.QUEUED), runBlocking { vehicles.raiseMileage(3, 24500) })
        assertEquals(
            Outcome.Success(Recorded.QUEUED),
            runBlocking {
                maintenance.logMaintenance(3, LocalDate.of(2026, 11, 9), 24100, setOf(1L), null, clientEntryId = id, raiseMileageTo = 24100)
            },
        )
        val items = items(pending)
        assertEquals(listOf(PendingItem.Kind.MILEAGE, PendingItem.Kind.MAINTENANCE), items.map { it.kind })
        assertEquals(entryId, items[1].id) // the log keeps its id, so sending it later cannot duplicate it
        assertEquals(setOf(1L), items[1].taskIds)
        assertEquals(2, scheduled)
    }

    @Test
    fun theQueueSurvivesARestart() {
        val (_, pending) = setUp()
        runBlocking { pending.enqueue(logEntry()) }
        val reopened = PendingRepository(FileOutbox(File(tmp.root, "outbox/entries.json"), Serializer.kotlinxSerializationJson))
        assertEquals(1, runBlocking { reopened.count() })
        assertEquals(entryId, items(reopened).single().id)
    }

    @Test
    fun clearRemovesTheFile() {
        val (_, pending) = setUp()
        runBlocking { pending.enqueue(logEntry()) }
        runBlocking { pending.clear() }
        assertTrue(File(tmp.root, "outbox").listFiles().orEmpty().isEmpty())
    }

    private companion object {
        const val VEHICLE = """{"id":3,"name":"Tenere","type":"MOTORCYCLE","modelYear":2021,"firstRegistrationDate":"2021-05-04",
            "currentMileage":24500,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}"""
    }
}
