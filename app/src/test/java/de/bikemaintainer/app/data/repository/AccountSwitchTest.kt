package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.infrastructure.Serializer
import de.bikemaintainer.app.api.models.VehicleResponse
import de.bikemaintainer.app.data.repository.RepositoryTestEnv.Companion.json
import de.bikemaintainer.app.data.storage.FileOfflineStore
import de.bikemaintainer.app.data.storage.FileOutbox
import de.bikemaintainer.app.data.storage.OutboxEntry
import de.bikemaintainer.app.domain.Outcome
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Someone else signs in on the same device (app#97): what person A recorded without a connection must not be sent with
 * the session of person B, and an answer that arrives after a logout must not end up in B's offline data.
 */
class AccountSwitchTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var env: RepositoryTestEnv
    private lateinit var outbox: FileOutbox
    private lateinit var offline: FileOfflineStore
    private lateinit var pending: PendingRepository
    private lateinit var sync: OutboxSync
    private lateinit var auth: ApiAuthRepository
    private var scheduled = 0
    private var cancelled = 0
    private var now = 1_000_000_000_000L

    /** Runs while the server answers a request, before the answer reaches the app. */
    private var duringRequest: (RecordedRequest) -> Unit = {}

    @After fun tearDown() = env.close()

    private fun setUp(mode: String = "household") {
        env = RepositoryTestEnv(tmp.newFile("s.preferences_pb")) { req ->
            duringRequest(req)
            answer(req, mode)
        }
        outbox = FileOutbox(File(tmp.root, "outbox/entries.json"), Serializer.kotlinxSerializationJson)
        offline = FileOfflineStore(File(tmp.root, "offline"), Serializer.kotlinxSerializationJson)
        val owner = env.settings.settings.map { it.owner }
        val scheduler = object : OutboxScheduler {
            override fun schedule() {
                scheduled++
            }

            override fun cancel() {
                cancelled++
            }
        }
        pending = PendingRepository(outbox, owner, { now }, scheduler)
        sync = OutboxSync(env.provider, outbox, owner) { now }
        auth = ApiAuthRepository(env.provider, env.settings, env.tokens, env.state, offline, pending)
    }

    private fun answer(req: RecordedRequest, mode: String): MockResponse {
        val path = req.url.encodedPath
        return when {
            path == "/api/v1/config" ->
                json("""{"mode":"$mode"}""").newBuilder().addHeader("Set-Cookie", "XSRF-TOKEN=csrf-1; Path=/").build()
            path.endsWith("/select") -> json(user(path.split('/').dropLast(1).last().toLong()))
            path.endsWith("/device-login") -> json("""{"user":${user(USER_A)},"deviceToken":"tok-a"}""")
            path.endsWith("/logout") -> MockResponse.Builder().code(204).build()
            path == "/api/v1/vehicles" -> json("[$VEHICLE]")
            path.endsWith("/mileage") -> json(VEHICLE)
            else -> json("{}", 404)
        }
    }

    private fun user(id: Long) = """{"id":$id,"role":"USER","language":"de","currency":"EUR","active":true,
        "accessStatus":"TRIAL","hasSubscription":false,"emailVerified":true,"createdAt":"2026-01-01T00:00:00Z"}"""

    private fun select(profile: Long) = assertEquals(Outcome.Success(Unit), runBlocking { auth.selectProfile(env.url, profile) })

    private fun recordMileage(id: String) = runBlocking { pending.enqueue(OutboxEntry(id, 3, now, mileage = 24500)) }

    private fun puts() = env.requests.count { it.method == "PUT" }

    private fun visible() = runBlocking { pending.items.first().map { it.id } }

    @Test
    fun entriesOfAAreNotSentAfterBChoseTheirProfile() {
        setUp()
        select(USER_A)
        recordMileage("a1")
        // A's session runs out, B picks their profile without A logging out.
        select(USER_B)

        assertEquals(SyncResult.DONE, runBlocking { sync.run() })
        assertEquals(0, puts())
        assertTrue("B does not see A's entries", visible().isEmpty())
        assertEquals("nor are they counted in B's logout warning", 0, runBlocking { pending.count() })

        // A comes back: the entries are A's again and go out.
        select(USER_A)
        assertEquals(listOf("a1"), visible())
        assertEquals(SyncResult.DONE, runBlocking { sync.run() })
        assertEquals(1, puts())
        assertTrue(visible().isEmpty())
    }

    @Test
    fun aRunStartedForACannotSendAsEntriesOnceBIsSignedIn() {
        setUp()
        select(USER_A)
        recordMileage("a1")
        recordMileage("a2")
        // B chooses their profile while the run for A is sending a1.
        duringRequest = { req -> if (req.method == "PUT") runBlocking { auth.selectProfile(env.url, USER_B) } }
        runBlocking { sync.run() }
        duringRequest = {}
        assertEquals("a1 was on its way, a2 must not follow", 1, puts())
        select(USER_A)
        assertEquals("a1 is through and not sent again", listOf("a2"), visible())
    }

    @Test
    fun newLoginCancelsARunOfThePreviousPersonAndSchedulesOnlyForOwnEntries() {
        setUp()
        select(USER_A)
        recordMileage("a1")
        scheduled = 0
        cancelled = 0
        select(USER_B)
        assertEquals(1, cancelled)
        assertEquals("nothing of B's to send", 0, scheduled)
    }

    @Test
    fun logoutOfBKeepsAsEntriesAndCancelsARunningSync() {
        setUp()
        select(USER_A)
        recordMileage("a1")
        select(USER_B)
        recordMileage("b1")
        cancelled = 0

        runBlocking { auth.logout() }
        assertEquals(1, cancelled)
        select(USER_A)
        assertEquals(listOf("a1"), visible())
    }

    @Test
    fun logoutDropsOwnEntries() {
        setUp()
        select(USER_A)
        recordMileage("a1")
        runBlocking { auth.logout() }
        select(USER_A)
        assertTrue(visible().isEmpty())
    }

    @Test
    fun switchingTheServerDropsEveryonesEntries() {
        setUp()
        select(USER_A)
        recordMileage("a1")
        select(USER_B)
        runBlocking { auth.switchServer() }
        select(USER_A)
        assertTrue(visible().isEmpty())
    }

    @Test
    fun entriesOfAnotherPersonAreDroppedThirtyDaysAfterTheyWereHidden() {
        setUp()
        select(USER_A)
        recordMileage("a1")
        now += TimeUnit.DAYS.toMillis(20) // waited 20 days before B signed in: the 30 days count from then
        select(USER_B)
        now += TimeUnit.DAYS.toMillis(29)
        select(USER_B)
        select(USER_A)
        assertEquals(listOf("a1"), visible())

        select(USER_B)
        now += TimeUnit.DAYS.toMillis(31)
        runBlocking { pending.items.first() } // opening the list tidies up
        select(USER_A)
        assertTrue(visible().isEmpty())
    }

    @Test
    fun portalLoginStoresTheAccountAsOwnerOfNewEntries() {
        setUp(mode = "portal")
        assertEquals(Outcome.Success(Unit), runBlocking { auth.deviceLogin(env.url, "a@b.de", "pw", "Pixel") })
        recordMileage("a1")
        assertEquals("${env.url}#$USER_A", runBlocking { outbox.load().single().owner })
    }

    @Test
    fun answerArrivingAfterLogoutDoesNotFillTheOfflineDataOfTheNextPerson() {
        setUp()
        select(USER_A)
        val vehicles = ApiVehicleRepository(env.provider, offline)
        duringRequest = { req -> if (req.url.encodedPath == "/api/v1/vehicles") runBlocking { auth.logout() } }
        runBlocking { vehicles.vehicles() }
        duringRequest = {}

        assertNull(runBlocking { offline.load(OfflineKeys.VEHICLES, kotlinx.serialization.builtins.ListSerializer(VehicleResponse.serializer())) })
        assertNull(runBlocking { offline.load(OfflineKeys.vehicle(3), VehicleResponse.serializer()) })
    }

    private companion object {
        const val USER_A = 7L
        const val USER_B = 8L
        const val VEHICLE = """{"id":3,"name":"Tenere","type":"MOTORCYCLE","modelYear":2021,"firstRegistrationDate":"2021-05-04",
            "currentMileage":24500,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}"""
    }
}
