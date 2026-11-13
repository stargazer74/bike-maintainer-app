package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.data.repository.RepositoryTestEnv.Companion.json
import de.bikemaintainer.app.domain.Access
import de.bikemaintainer.app.domain.AccessStatus
import de.bikemaintainer.app.domain.Account
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Profile
import de.bikemaintainer.app.domain.ServerConfig
import de.bikemaintainer.app.domain.ServerMode
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import de.bikemaintainer.app.data.storage.FakeTokenCipher
import de.bikemaintainer.app.data.storage.OfflineStore
import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.data.storage.TokenCipher
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuthRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var env: RepositoryTestEnv

    @After
    fun tearDown() {
        env.close()
    }

    private val user = """{"id":1,"role":"USER","language":"de","currency":"EUR","active":true,"accessStatus":"TRIAL","hasSubscription":false,"emailVerified":true,
        "createdAt":"2026-01-01T00:00:00Z"}"""

    private fun setUp(
        cipher: TokenCipher = FakeTokenCipher(),
        wrap: (DataStore<Preferences>) -> DataStore<Preferences> = { it },
        handler: (mockwebserver3.RecordedRequest) -> MockResponse = { json("{}") },
    ): ApiAuthRepository {
        env = RepositoryTestEnv(tmp.newFile("s.preferences_pb"), cipher, wrap, handler)
        return ApiAuthRepository(env.provider, env.settings, env.tokens, env.state)
    }

    /** Answers GET /config like the backend (sets the CSRF cookie); everything else goes to [handler]. */
    private fun withConfig(
        mode: String = "portal",
        handler: (mockwebserver3.RecordedRequest) -> MockResponse,
    ): (mockwebserver3.RecordedRequest) -> MockResponse = { req ->
        if (req.url.encodedPath == "/api/v1/config") {
            json("""{"mode":"$mode"}""").newBuilder().addHeader("Set-Cookie", "XSRF-TOKEN=csrf-1; Path=/").build()
        } else {
            handler(req)
        }
    }

    /** Like Spring's csrf.spa(): a POST without the matching X-XSRF-TOKEN header is rejected with 403. */
    private fun csrfChecked(handler: (mockwebserver3.RecordedRequest) -> MockResponse) = withConfig { req ->
        if (req.method == "POST" && req.headers["X-XSRF-TOKEN"] != "csrf-1") {
            json("""{"code":"FORBIDDEN"}""", 403)
        } else {
            handler(req)
        }
    }

    @Test
    fun deviceLoginAfterLogoutFetchesCsrfCookieFirst() {
        val repo = setUp(handler = csrfChecked { req ->
            if (req.url.encodedPath.endsWith("/logout")) MockResponse.Builder().code(204).build()
            else json("""{"user":$user,"deviceToken":"tok"}""")
        })
        runBlocking {
            env.settings.setServer(env.url, ServerMode.PORTAL)
            assertEquals(Outcome.Success(ServerConfig(ServerMode.PORTAL, null)), repo.fetchConfig(env.url))
            repo.logout() // resets the provider and with it the cookie jar
        }
        assertEquals(Outcome.Success(Unit), runBlocking { repo.deviceLogin(env.url, "a@b.de", "pw", "Pixel") })
    }

    @Test
    fun deviceLoginWithoutPriorConfigFetchesCsrfCookie() {
        val repo = setUp(handler = csrfChecked { json("""{"user":$user,"deviceToken":"tok"}""") })
        assertEquals(Outcome.Success(Unit), runBlocking { repo.deviceLogin(env.url, "a@b.de", "pw", "Pixel") })
        assertEquals(listOf("/api/v1/config", "/api/v1/auth/device-login"), env.requests.map { it.url.encodedPath })
    }

    @Test
    fun deviceLoginReusesExistingCsrfCookie() {
        val repo = setUp(handler = csrfChecked { json("""{"user":$user,"deviceToken":"tok"}""") })
        runBlocking { repo.fetchConfig(env.url) }
        assertEquals(Outcome.Success(Unit), runBlocking { repo.deviceLogin(env.url, "a@b.de", "pw", "Pixel") })
        assertEquals(1, env.requests.count { it.url.encodedPath == "/api/v1/config" })
    }

    @Test
    fun selectProfileAfterLogoutFetchesCsrfCookieFirst() {
        val repo = setUp(handler = csrfChecked { req ->
            if (req.url.encodedPath.endsWith("/logout")) MockResponse.Builder().code(204).build() else json(user)
        })
        runBlocking {
            env.settings.setServer(env.url, ServerMode.HOUSEHOLD)
            repo.fetchConfig(env.url)
            repo.logout()
        }
        assertEquals(Outcome.Success(Unit), runBlocking { repo.selectProfile(env.url, 7) })
        assertEquals(7L, runBlocking { env.settings.current() }.profileId)
    }

    @Test
    fun csrfFetchFailureIsReportedLikeALoginFailure() {
        val repo = setUp { MockResponse.Builder().code(503).build() }
        assertEquals(
            Outcome.Failure(AppError.Http(503, null)),
            runBlocking { repo.deviceLogin(env.url, "a@b.de", "pw", "Pixel") },
        )
        assertEquals(
            Outcome.Failure(AppError.Http(503, null)),
            runBlocking { repo.selectProfile(env.url, 7) },
        )
        assertEquals(listOf("/api/v1/config", "/api/v1/config"), env.requests.map { it.url.encodedPath })
        env.server.close()
        assertEquals(Outcome.Failure(AppError.Network), runBlocking { repo.deviceLogin(env.url, "a@b.de", "pw", "Pixel") })
    }

    @Test
    fun fetchConfigReadsModeAndWebsiteUrl() {
        val repo = setUp { json("""{"mode":"household"}""") }
        assertEquals(Outcome.Success(ServerConfig(ServerMode.HOUSEHOLD, null)), runBlocking { repo.fetchConfig(env.url) })
    }

    @Test
    fun fetchConfigReadsWebsiteUrl() {
        val repo = setUp { json("""{"mode":"portal","websiteUrl":"https://bike-maintainer.de"}""") }
        assertEquals(
            Outcome.Success(ServerConfig(ServerMode.PORTAL, "https://bike-maintainer.de")),
            runBlocking { repo.fetchConfig(env.url) },
        )
    }

    @Test
    fun nonHttpWebsiteUrlIsTreatedAsAbsent() {
        for (site in listOf("javascript:alert(1)", "file:///etc/passwd", "intent://x#Intent;end", "ftp://w")) {
            env = RepositoryTestEnv(tmp.newFile(), handler = { json("""{"mode":"portal","websiteUrl":"$site"}""") })
            val repo = ApiAuthRepository(env.provider, env.settings, env.tokens, env.state)
            assertEquals(site, Outcome.Success(ServerConfig(ServerMode.PORTAL, null)), runBlocking { repo.fetchConfig(env.url) })
            env.close()
        }
        val repo = setUp { json("""{"mode":"portal","websiteUrl":"HTTP://w.example"}""") }
        assertEquals(
            Outcome.Success(ServerConfig(ServerMode.PORTAL, "HTTP://w.example")),
            runBlocking { repo.fetchConfig(env.url) },
        )
    }

    @Test
    fun deviceLoginStoresServerModeAndToken() {
        val repo = setUp(handler = withConfig { json("""{"user":$user,"deviceToken":"tok"}""") })
        val generation = env.state.generation
        assertEquals(Outcome.Success(Unit), runBlocking { repo.deviceLogin(env.url, "a@b.de", "pw", "Pixel") })
        val stored = runBlocking { env.settings.current() }
        assertEquals(env.url, stored.serverUrl)
        assertEquals(ServerMode.PORTAL, stored.mode)
        assertEquals("tok", runBlocking { env.tokens.get() })
        assertEquals(generation + 1, env.state.generation)
    }

    @Test
    fun deviceLoginFailureStoresNothing() {
        val repo = setUp(handler = withConfig { json("""{"code":"EMAIL_NOT_VERIFIED"}""", 403) })
        assertEquals(
            Outcome.Failure(AppError.Http(403, "EMAIL_NOT_VERIFIED")),
            runBlocking { repo.deviceLogin(env.url, "a@b.de", "pw", "Pixel") },
        )
        assertNull(runBlocking { env.settings.current() }.serverUrl)
        assertNull(runBlocking { env.tokens.get() })
    }

    @Test
    fun selectProfileStoresHouseholdProfile() {
        val repo = setUp(handler = withConfig("household") { req ->
            if (req.url.encodedPath.endsWith("/select")) json(user) else json("""[{"id":7,"displayName":"Chris","vehicleCount":0}]""")
        })
        assertEquals(Outcome.Success(listOf(Profile(7, "Chris"))), runBlocking { repo.profiles(env.url) })
        assertEquals(Outcome.Success(Unit), runBlocking { repo.selectProfile(env.url, 7) })
        val stored = runBlocking { env.settings.current() }
        assertEquals(env.url, stored.serverUrl)
        assertEquals(ServerMode.HOUSEHOLD, stored.mode)
        assertEquals(7L, stored.profileId)
    }

    @Test
    fun candidateServerNeverReceivesStoredToken() {
        val repo = setUp { MockResponse.Builder().code(401).build() }
        runBlocking {
            env.settings.setServer("https://stored.example/", ServerMode.PORTAL)
            env.tokens.set("tok")
        }
        assertEquals(Outcome.Failure(AppError.Unauthorized), runBlocking { repo.profiles(env.url) })
        assertEquals(listOf("/api/v1/profiles"), env.requests.map { it.url.encodedPath })
        assertEquals("tok", runBlocking { env.tokens.get() })
    }

    /** Lets [writesLeft] DataStore writes through, then fails every further one like a full disk. */
    private val writesLeft = AtomicInteger(Int.MAX_VALUE)

    private fun limitedWrites(real: DataStore<Preferences>) = object : DataStore<Preferences> {
        override val data = real.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (writesLeft.getAndDecrement() <= 0) throw IOException("disk full")
            return real.updateData(transform)
        }
    }

    @Test
    fun selectProfileStoresEverythingInOneWrite() {
        val repo = setUp(wrap = ::limitedWrites, handler = withConfig("household") { json(user) })
        runBlocking {
            env.settings.setServer("https://previous.example/", ServerMode.PORTAL)
            env.tokens.set("tok")
            env.settings.setProfileId(5)
        }
        writesLeft.set(1)
        assertEquals(Outcome.Success(Unit), runBlocking { repo.selectProfile(env.url, 7) })
        assertEquals(StoredSettings(env.url, ServerMode.HOUSEHOLD, null, 7), runBlocking { env.settings.current() })
    }

    @Test
    fun deviceLoginStoresEverythingInOneWrite() {
        val repo = setUp(wrap = ::limitedWrites, handler = withConfig { json("""{"user":$user,"deviceToken":"tok"}""") })
        runBlocking {
            env.settings.setServer("http://192.168.0.2:8080/", ServerMode.HOUSEHOLD)
            env.settings.setProfileId(5)
        }
        writesLeft.set(1)
        assertEquals(Outcome.Success(Unit), runBlocking { repo.deviceLogin(env.url, "a@b.de", "pw", "Pixel") })
        assertEquals(StoredSettings(env.url, ServerMode.PORTAL, "enc:tok", null), runBlocking { env.settings.current() })
    }

    @Test
    fun failedSelectProfileWriteKeepsPreviousState() {
        val repo = setUp(wrap = ::limitedWrites, handler = withConfig("household") { json(user) })
        runBlocking {
            env.settings.setServer("https://previous.example/", ServerMode.HOUSEHOLD)
            env.settings.setProfileId(5)
        }
        val before = runBlocking { env.settings.current() }
        writesLeft.set(0)
        assertEquals(Outcome.Failure(AppError.Storage), runBlocking { repo.selectProfile(env.url, 7) })
        assertEquals(before, runBlocking { env.settings.current() })
    }

    @Test
    fun logoutClearsSessionEvenWhenOffline() {
        val repo = setUp { json("{}") }
        runBlocking {
            env.settings.setServer(env.url, ServerMode.PORTAL)
            env.tokens.set("tok")
            env.settings.setProfileId(5)
        }
        env.server.close()
        assertEquals(Outcome.Failure(AppError.Network), runBlocking { repo.logout() })
        val stored = runBlocking { env.settings.current() }
        assertNull(stored.encryptedDeviceToken)
        assertNull(stored.profileId)
        assertEquals(env.url, stored.serverUrl)
    }

    /** Offline data that remembers whether it was cleared. */
    private class RecordingOffline : de.bikemaintainer.app.data.storage.OfflineStore by
        de.bikemaintainer.app.data.storage.OfflineStore.None {
        var clears = 0

        override suspend fun clear() {
            clears++
        }
    }

    @Test
    fun logoutClearsOfflineDataEvenWhenOffline() {
        setUp()
        val offline = RecordingOffline()
        val repo = ApiAuthRepository(env.provider, env.settings, env.tokens, env.state, offline)
        runBlocking { env.settings.setServer(env.url, ServerMode.PORTAL) }
        env.server.close()
        runBlocking { repo.logout() }
        assertEquals(1, offline.clears)
    }

    @Test
    fun logoutDropsWhatWasNotSentButANewLoginKeepsItAndSendsIt() {
        setUp(handler = withConfig("household") { json(user) })
        val outbox = de.bikemaintainer.app.data.storage.InMemoryOutbox()
        var scheduled = 0
        val pending = PendingRepository(outbox) { scheduled++ }
        val repo = ApiAuthRepository(env.provider, env.settings, env.tokens, env.state, OfflineStore.None, pending)
        runBlocking { outbox.add(de.bikemaintainer.app.data.storage.OutboxEntry("a", 3, 0, mileage = 1)) }

        assertEquals(Outcome.Success(Unit), runBlocking { repo.selectProfile(env.url, 7) })
        assertEquals(1, runBlocking { pending.count() })
        assertEquals(1, scheduled)

        runBlocking { repo.logout() }
        assertEquals(0, runBlocking { pending.count() })
    }

    @Test
    fun selectingAProfileClearsOfflineDataOfThePreviousPerson() {
        setUp(handler = withConfig("household") { json(user) })
        val offline = RecordingOffline()
        val repo = ApiAuthRepository(env.provider, env.settings, env.tokens, env.state, offline)
        assertEquals(Outcome.Success(Unit), runBlocking { repo.selectProfile(env.url, 7) })
        assertEquals(1, offline.clears)
    }

    @Test
    fun switchServerClearsEverything() {
        val repo = setUp { MockResponse.Builder().code(204).build() }
        runBlocking {
            env.settings.setServer(env.url, ServerMode.PORTAL)
            env.tokens.set("tok")
            repo.switchServer()
        }
        val stored = runBlocking { env.settings.current() }
        assertNull(stored.serverUrl)
        assertNull(stored.mode)
        assertNull(stored.encryptedDeviceToken)
        assertNotNull(env.requests.singleOrNull { it.url.encodedPath.endsWith("/logout") })
    }

    @Test
    fun invalidServerAddressIsReported() {
        val repo = setUp()
        val bad = Outcome.Failure(AppError.InvalidAddress)
        assertEquals(bad, runBlocking { repo.fetchConfig("not a url") })
        assertEquals(bad, runBlocking { repo.deviceLogin("not a url", "a@b.de", "pw", "Pixel") })
        assertEquals(bad, runBlocking { repo.profiles("") })
        assertEquals(bad, runBlocking { repo.selectProfile("ftp://x", 1) })
        assertEquals(0, env.requests.size)
    }

    @Test
    fun storageFailureDuringLoginIsReported() {
        val failing = object : TokenCipher {
            override fun encrypt(plain: String): String = throw GeneralSecurityException("keystore")
            override fun decrypt(encrypted: String): String = throw GeneralSecurityException("keystore")
        }
        val repo = setUp(cipher = failing, handler = withConfig { json("""{"user":$user,"deviceToken":"tok"}""") })
        assertEquals(Outcome.Failure(AppError.Storage), runBlocking { repo.deviceLogin(env.url, "a@b.de", "pw", "Pixel") })
        assertNull(runBlocking { env.settings.current() }.serverUrl)
    }

    @Test
    fun logoutResetsProviderEvenIfStorageFails() {
        val repo = setUp(wrap = { real ->
            object : DataStore<Preferences> {
                override val data = real.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    throw IOException("disk full")
            }
        })
        val before = env.provider.apisFor(env.url)
        assertEquals(Outcome.Failure(AppError.Storage), runBlocking { repo.logout() })
        assertNotSame(before, env.provider.apisFor(env.url))
    }

    @Test
    fun markNotificationsAskedSwallowsStorageFailure() {
        writesLeft.set(0)
        val repo = setUp(wrap = ::limitedWrites)
        runBlocking { repo.markNotificationsAsked() } // must not throw
        assertEquals(false, runBlocking { repo.settings.first() }.notificationsAsked)
    }

    @Test
    fun currentAccountMapsEmailAndDisplayName() {
        val repo = setUp { req ->
            assertEquals("/api/v1/auth/me", req.url.encodedPath)
            json("""{"id":1,"role":"USER","language":"de","currency":"EUR","active":true,"accessStatus":"TRIAL","hasSubscription":false,"emailVerified":true,
                "createdAt":"2026-01-01T00:00:00Z","email":"a@b.de"}""")
        }
        runBlocking { env.settings.setServer(env.url, ServerMode.PORTAL) }
        val expected = Account("a@b.de", null, Access(AccessStatus.TRIAL, null, false))
        assertEquals(Outcome.Success(expected), runBlocking { repo.currentAccount() })
        assertEquals(expected.access, repo.access.value)
    }

    @Test
    fun olderServerWithoutAccessFieldsMeansFullAccess() {
        val repo = setUp {
            json("""{"id":1,"role":"USER","language":"de","currency":"EUR","active":true,"emailVerified":true,
                "createdAt":"2026-01-01T00:00:00Z","displayName":"Chris"}""")
        }
        runBlocking { env.settings.setServer(env.url, ServerMode.HOUSEHOLD) }
        assertEquals(Outcome.Success(Account(null, "Chris", Access.FULL)), runBlocking { repo.currentAccount() })
    }

    @Test
    fun accessComesFromTheServerAndEndsWithTheLogout() {
        val repo = setUp { req ->
            if (req.url.encodedPath == "/api/v1/auth/me") {
                json("""{"id":1,"role":"USER","language":"de","currency":"EUR","active":true,"accessStatus":"EXPIRED",
                    "accessUntil":"2026-11-01","hasSubscription":true,"emailVerified":true,"createdAt":"2026-01-01T00:00:00Z"}""")
            } else {
                json("{}")
            }
        }
        runBlocking { env.settings.setServer(env.url, ServerMode.PORTAL) }
        runBlocking { repo.refreshAccess() }
        assertEquals(Access(AccessStatus.EXPIRED, java.time.LocalDate.of(2026, 11, 1), true), repo.access.value)
        runBlocking { repo.logout() }
        assertEquals(Access.FULL, repo.access.value)
    }

    /** An answer that arrives after the logout belongs to the old session and must not bring its access back. */
    @Test
    fun answerFromBeforeTheLogoutIsIgnored() {
        val asked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val repo = setUp { req ->
            if (req.url.encodedPath == "/api/v1/auth/me") {
                asked.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                json("""{"id":1,"role":"USER","language":"de","currency":"EUR","active":true,"accessStatus":"EXPIRED",
                    "hasSubscription":false,"emailVerified":true,"createdAt":"2026-01-01T00:00:00Z"}""")
            } else {
                json("{}")
            }
        }
        runBlocking { env.settings.setServer(env.url, ServerMode.PORTAL) }
        runBlocking {
            val pending = async(Dispatchers.IO) { repo.refreshAccess() }
            assertTrue(asked.await(5, java.util.concurrent.TimeUnit.SECONDS))
            repo.logout()
            release.countDown()
            pending.await()
        }
        assertEquals(Access.FULL, repo.access.value)
    }

    @Test
    fun failedAnswerKeepsTheLastKnownAccess() {
        var fail = false
        val repo = setUp {
            if (fail) {
                json("{}", 500)
            } else {
                json("""{"id":1,"role":"USER","language":"de","currency":"EUR","active":true,"accessStatus":"EXPIRED",
                    "hasSubscription":false,"emailVerified":true,"createdAt":"2026-01-01T00:00:00Z"}""")
            }
        }
        runBlocking { env.settings.setServer(env.url, ServerMode.PORTAL) }
        runBlocking { repo.refreshAccess() }
        fail = true
        runBlocking { repo.refreshAccess() }
        assertEquals(AccessStatus.EXPIRED, repo.access.value.status)
    }

    @Test
    fun subscriptionRequiredMakesTheAccountReadOnly() {
        val repo = setUp()
        repo.subscriptionRequired()
        assertEquals(AccessStatus.EXPIRED, repo.access.value.status)
    }

    @Test
    fun currentAccountMapsDisplayNameInHouseholdMode() {
        val repo = setUp {
            json("""{"id":1,"role":"USER","language":"de","currency":"EUR","active":true,"accessStatus":"FULL","hasSubscription":false,"emailVerified":true,
                "createdAt":"2026-01-01T00:00:00Z","displayName":"Chris"}""")
        }
        runBlocking { env.settings.setServer(env.url, ServerMode.HOUSEHOLD) }
        assertEquals(Outcome.Success(Account(null, "Chris")), runBlocking { repo.currentAccount() })
    }

    @Test
    fun currentAccountWithoutServerIsNotConfigured() {
        val repo = setUp()
        assertEquals(Outcome.Failure(AppError.NotConfigured), runBlocking { repo.currentAccount() })
    }

    @Test
    fun markNotificationsAskedIsExposedInSettings() {
        val repo = setUp()
        runBlocking { repo.markNotificationsAsked() }
        assertEquals(true, runBlocking { repo.settings.first() }.notificationsAsked)
    }

    @Test
    fun notificationsSwitchIsSavedAndExposed() {
        val repo = setUp()
        runBlocking { repo.setNotificationsEnabled(false) }
        assertEquals(false, runBlocking { repo.settings.first() }.notificationsEnabled)
    }

    @Test
    fun notificationsSwitchSwallowsStorageFailure() {
        writesLeft.set(0)
        val repo = setUp(wrap = ::limitedWrites)
        runBlocking { repo.setNotificationsEnabled(false) } // must not throw
        assertEquals(true, runBlocking { repo.settings.first() }.notificationsEnabled)
    }

    /** A real challenge of the backend (see AltchaSolverTest): the solver needs a few hundred attempts. */
    private val challenge = de.bikemaintainer.app.domain.scan.testResource("altcha/challenge-2.json").readText().trim()

    @Test
    fun registerSolvesTheChallengeAndSendsThePayload() {
        val bodies = mutableListOf<String>()
        val repo = setUp(handler = csrfChecked { req ->
            when (req.url.encodedPath) {
                "/api/v1/auth/challenge" -> json(challenge)
                "/api/v1/auth/register" -> {
                    bodies += req.body!!.utf8()
                    MockResponse.Builder().code(202).build()
                }
                else -> json("{}", 404)
            }
        })
        val result = runBlocking { repo.register(env.url, "max@example.org", "geheim-1234", german = false) }
        assertEquals(Outcome.Success(Unit), result)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals("max@example.org", body.getValue("email").jsonPrimitive.content)
        assertEquals("en", body.getValue("language").jsonPrimitive.content)
        val payload = String(java.util.Base64.getDecoder().decode(body.getValue("altcha").jsonPrimitive.content))
        assertTrue(payload, payload.startsWith("""{"challenge":$challenge,"solution":{"counter":308,"""))
        // The honeypot stays empty.
        assertTrue(body["website"] == null || body["website"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun registerReportsCaptchaAndQuotaErrors() {
        var answer = json("""{"code":"CAPTCHA_INVALID"}""", 400)
        val repo = setUp(handler = csrfChecked { req ->
            if (req.url.encodedPath == "/api/v1/auth/challenge") json(challenge) else answer
        })
        assertEquals(
            Outcome.Failure(AppError.Http(400, "CAPTCHA_INVALID")),
            runBlocking { repo.register(env.url, "a@b.de", "geheim-1234", german = true) },
        )
        answer = json("""{"code":"MAIL_QUOTA_EXCEEDED"}""", 429)
        assertEquals(
            Outcome.Failure(AppError.Http(429, "MAIL_QUOTA_EXCEEDED")),
            runBlocking { repo.register(env.url, "a@b.de", "geheim-1234", german = true) },
        )
    }

    @Test
    fun unusableChallengeIsReportedWithoutSending() {
        var posts = 0
        val repo = setUp(handler = csrfChecked { req ->
            if (req.url.encodedPath == "/api/v1/auth/challenge") json("""{"parameters":{}}""")
            else MockResponse.Builder().code(202).build().also { posts++ }
        })
        assertEquals(
            Outcome.Failure(AppError.Http(-1, null)),
            runBlocking { repo.register(env.url, "a@b.de", "geheim-1234", german = true) },
        )
        assertEquals(0, posts)
    }

    @Test
    fun resendAnswersWithoutBody() {
        val repo = setUp(handler = csrfChecked { req ->
            if (req.url.encodedPath == "/api/v1/auth/challenge") json(challenge) else MockResponse.Builder().code(204).build()
        })
        assertEquals(Outcome.Success(Unit), runBlocking { repo.resendVerification(env.url, "a@b.de") })
    }

    @Test
    fun createProfileReturnsTheNewProfileAndReportsATakenName() {
        var taken = false
        val repo = setUp(handler = csrfChecked {
            if (taken) json("""{"code":"CONFLICT"}""", 409) else json("""{"id":7,"displayName":"Chris","vehicleCount":0}""", 201)
        })
        assertEquals(Outcome.Success(Profile(7, "Chris")), runBlocking { repo.createProfile(env.url, "Chris") })
        taken = true
        assertEquals(Outcome.Failure(AppError.Http(409, "CONFLICT")), runBlocking { repo.createProfile(env.url, "Chris") })
    }

    @Test
    fun deleteAccountSendsThePasswordAndDropsEverythingLocal() {
        val deletes = mutableListOf<mockwebserver3.RecordedRequest>()
        val repo = setUp(handler = withConfig { req ->
            if (req.method == "DELETE") deletes += req
            MockResponse.Builder().code(204).build()
        })
        runBlocking {
            env.settings.setServer(env.url, ServerMode.PORTAL)
            env.tokens.set("tok")
        }
        assertEquals(Outcome.Success(Unit), runBlocking { repo.deleteAccount("geheim-1234") })
        val delete = deletes.single()
        assertEquals("/api/v1/auth/me", delete.url.encodedPath)
        assertEquals("csrf-1", delete.headers["X-XSRF-TOKEN"])
        val body = kotlinx.serialization.json.Json.parseToJsonElement(delete.body!!.utf8()).jsonObject
        assertEquals("geheim-1234", body.getValue("password").jsonPrimitive.content)
        val stored = runBlocking { env.settings.current() }
        assertNull(stored.serverUrl)
        assertNull(stored.mode)
        assertNull(stored.encryptedDeviceToken)
    }

    @Test
    fun deleteAccountKeepsEverythingWhenTheServerRefuses() {
        val repo = setUp(handler = withConfig { json("""{"code":"WRONG_PASSWORD"}""", 403) })
        runBlocking {
            env.settings.setServer(env.url, ServerMode.PORTAL)
            env.tokens.set("tok")
        }
        assertEquals(
            Outcome.Failure(AppError.Http(403, "WRONG_PASSWORD")),
            runBlocking { repo.deleteAccount("falsch") },
        )
        val stored = runBlocking { env.settings.current() }
        assertEquals(env.url, stored.serverUrl)
        assertNotNull(stored.encryptedDeviceToken)
    }

    @Test
    fun deleteAccountWithoutServerIsNotConfigured() {
        val repo = setUp()
        assertEquals(Outcome.Failure(AppError.NotConfigured), runBlocking { repo.deleteAccount("pw") })
        assertTrue(env.requests.isEmpty())
    }
}
