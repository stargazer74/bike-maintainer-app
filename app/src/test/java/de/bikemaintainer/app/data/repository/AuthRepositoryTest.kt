package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.data.repository.RepositoryTestEnv.Companion.json
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Profile
import de.bikemaintainer.app.domain.ServerConfig
import de.bikemaintainer.app.domain.ServerMode
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private val user = """{"id":1,"role":"USER","language":"de","currency":"EUR","active":true,"emailVerified":true,
        "createdAt":"2026-01-01T00:00:00Z"}"""

    private fun setUp(handler: (mockwebserver3.RecordedRequest) -> MockResponse = { json("{}") }): AuthRepository {
        env = RepositoryTestEnv(tmp.newFile("s.preferences_pb"), handler)
        return AuthRepository(env.provider, env.settings, env.tokens, env.state)
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
    fun deviceLoginStoresServerModeAndToken() {
        val repo = setUp { json("""{"user":$user,"deviceToken":"tok"}""") }
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
        val repo = setUp { json("""{"code":"EMAIL_NOT_VERIFIED"}""", 403) }
        assertEquals(
            Outcome.Failure(AppError.Http(403, "EMAIL_NOT_VERIFIED")),
            runBlocking { repo.deviceLogin(env.url, "a@b.de", "pw", "Pixel") },
        )
        assertNull(runBlocking { env.settings.current() }.serverUrl)
        assertNull(runBlocking { env.tokens.get() })
    }

    @Test
    fun selectProfileStoresHouseholdProfile() {
        val repo = setUp { req ->
            if (req.url.encodedPath.endsWith("/select")) json(user) else json("""[{"id":7,"displayName":"Chris"}]""")
        }
        assertEquals(Outcome.Success(listOf(Profile(7, "Chris"))), runBlocking { repo.profiles(env.url) })
        assertEquals(Outcome.Success(Unit), runBlocking { repo.selectProfile(env.url, 7) })
        val stored = runBlocking { env.settings.current() }
        assertEquals(env.url, stored.serverUrl)
        assertEquals(ServerMode.HOUSEHOLD, stored.mode)
        assertEquals(7L, stored.profileId)
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
}
