package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.data.repository.RepositoryTestEnv.Companion.json
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Profile
import de.bikemaintainer.app.domain.ServerConfig
import de.bikemaintainer.app.domain.ServerMode
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import de.bikemaintainer.app.data.storage.FakeTokenCipher
import de.bikemaintainer.app.data.storage.TokenCipher
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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

    private fun setUp(
        cipher: TokenCipher = FakeTokenCipher(),
        wrap: (DataStore<Preferences>) -> DataStore<Preferences> = { it },
        handler: (mockwebserver3.RecordedRequest) -> MockResponse = { json("{}") },
    ): AuthRepository {
        env = RepositoryTestEnv(tmp.newFile("s.preferences_pb"), cipher, wrap, handler)
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
        val repo = setUp(cipher = failing) { json("""{"user":$user,"deviceToken":"tok"}""") }
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
}
