package de.bikemaintainer.app.data.network

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import de.bikemaintainer.app.api.apis.AuthApi
import de.bikemaintainer.app.api.apis.ProfilesApi
import de.bikemaintainer.app.api.apis.VehiclesApi
import de.bikemaintainer.app.api.infrastructure.Serializer
import de.bikemaintainer.app.data.storage.DeviceTokenStore
import de.bikemaintainer.app.data.storage.FakeTokenCipher
import de.bikemaintainer.app.data.storage.SettingsStore
import de.bikemaintainer.app.data.storage.TokenCipher
import java.security.GeneralSecurityException
import de.bikemaintainer.app.domain.ServerMode
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Cookie
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class SessionAuthenticatorTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val server = MockWebServer()

    /** Paths of all requests the server saw, in order. */
    private val seen = Collections.synchronizedList(mutableListOf<String>())

    private lateinit var settings: SettingsStore
    private lateinit var tokens: DeviceTokenStore
    private lateinit var state: SessionState
    private lateinit var cookieJar: InMemoryCookieJar
    private lateinit var client: OkHttpClient

    @After
    fun tearDown() {
        server.close()
        storeScope.cancel()
    }

    private fun setUp(
        mode: ServerMode = ServerMode.PORTAL,
        token: String? = "alt",
        cipher: TokenCipher = FakeTokenCipher(),
        profileId: Long? = null,
        handler: (RecordedRequest) -> MockResponse,
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += "${request.method} ${request.url.encodedPath}"
                return handler(request)
            }
        }
        server.start()
        settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = storeScope) { tmpFolder.newFile("t.preferences_pb") },
        )
        tokens = DeviceTokenStore(settings, cipher)
        runBlocking {
            settings.setServer(server.url("/").toString(), mode)
            if (token != null) DeviceTokenStore(settings, FakeTokenCipher()).set(token)
            settings.setProfileId(profileId)
        }
        state = SessionState()
        cookieJar = InMemoryCookieJar()
        lateinit var built: OkHttpClient
        val authenticator = SessionAuthenticator(settings, tokens, state) {
            val retrofit = retrofit(SessionAuthenticator.sessionClient(built))
            retrofit.create(AuthApi::class.java) to retrofit.create(ProfilesApi::class.java)
        }
        built = HttpClientFactory.create(cookieJar, authenticator, listOf(GenerationTagInterceptor(state)))
        client = built
    }

    private fun retrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(client)
        .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
        .build()

    private fun get(path: String) = client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    private fun post(path: String) = client.newCall(
        Request.Builder().url(server.url(path)).post("{}".toRequestBody("application/json".toMediaType())).build(),
    ).execute()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    private fun exchanged(token: String = "neu") = MockResponse.Builder()
        .addHeader("Content-Type", "application/json")
        .addHeader("Set-Cookie", "SESSION=s2; Path=/")
        .addHeader("Set-Cookie", "XSRF-TOKEN=neu; Path=/")
        .body("""{"user":$USER,"deviceToken":"$token"}""")
        .build()

    private fun RecordedRequest.hasNewSession() = headers["Cookie"].orEmpty().contains("SESSION=s2")

    private fun count(path: String) = seen.count { it.endsWith(path) }

    @Test
    fun unauthorizedTriggersExchangeAndRetry() {
        val exchangeBodies = mutableListOf<String>()
        setUp { req ->
            when (req.url.encodedPath) {
                VEHICLES -> if (req.hasNewSession()) json("[]") else status(401)
                DEVICE_SESSION -> exchanged().also { exchangeBodies += req.body!!.utf8() }
                else -> status(500)
            }
        }
        get(VEHICLES).use { assertEquals(200, it.code) }
        assertEquals(listOf("GET $VEHICLES", "POST $DEVICE_SESSION", "GET $VEHICLES"), seen.toList())
        assertEquals(listOf("""{"deviceToken":"alt"}"""), exchangeBodies)
        assertEquals("neu", runBlocking { tokens.get() })
        assertEquals(SessionStatus.ACTIVE, state.status.value)
        assertEquals(1L, state.generation)
    }

    @Test
    fun newTokenIsStoredBeforeRetry() {
        val tokenAtRetry = mutableListOf<String?>()
        setUp { req ->
            when (req.url.encodedPath) {
                VEHICLES -> if (req.hasNewSession()) {
                    tokenAtRetry += runBlocking { tokens.get() }
                    json("[]")
                } else {
                    status(401)
                }
                DEVICE_SESSION -> exchanged()
                else -> status(500)
            }
        }
        get(VEHICLES).use { assertEquals(200, it.code) }
        assertEquals(listOf<String?>("neu"), tokenAtRetry)
    }

    @Test
    fun retryAfterExchangeCarriesNewCsrfToken() {
        val csrf = mutableListOf<String?>()
        setUp { req ->
            when (req.url.encodedPath) {
                VEHICLES -> {
                    csrf += req.headers["X-XSRF-TOKEN"]
                    if (req.hasNewSession()) json("{}", 201) else status(401)
                }
                DEVICE_SESSION -> exchanged()
                else -> status(500)
            }
        }
        val url = server.url("/")
        cookieJar.saveFromResponse(url, listOf(Cookie.parse(url, "XSRF-TOKEN=alt; Path=/")!!))
        post(VEHICLES).use { assertEquals(201, it.code) }
        assertEquals(listOf<String?>("alt", "neu"), csrf)
    }

    // A deadlock (e.g. the exchange queued behind the waiting calls) would otherwise hang the build.
    @Test(timeout = 30_000)
    fun parallelRequestsExchangeOnlyOnce() {
        // OkHttp allows 5 concurrent calls per host; all 5 hold their slot while waiting for the renewal, so this also
        // shows that the exchange does not need a slot of the app client's dispatcher.
        val parallel = 5
        val allArrived = CountDownLatch(parallel)
        setUp { req ->
            when (req.url.encodedPath) {
                VEHICLES -> if (req.hasNewSession()) {
                    json("[]")
                } else {
                    // Hold the first answers until every call is in flight, so that they all see a 401 together.
                    allArrived.countDown()
                    allArrived.await(5, TimeUnit.SECONDS)
                    status(401)
                }
                DEVICE_SESSION -> exchanged()
                else -> status(500)
            }
        }
        val vehicles = retrofit(client).create(VehiclesApi::class.java)
        val codes = runBlocking {
            (1..parallel).map { async(Dispatchers.IO) { vehicles.listVehicles().code() } }.awaitAll()
        }
        assertEquals(List(parallel) { 200 }, codes)
        assertEquals(1, count(DEVICE_SESSION))
        assertEquals(parallel * 2, count(VEHICLES))
        assertEquals(1L, state.generation)
    }

    @Test
    fun changedGenerationSkipsSecondExchange() {
        val vehicleCalls = AtomicInteger()
        setUp { req ->
            when (req.url.encodedPath) {
                VEHICLES -> if (vehicleCalls.getAndIncrement() == 0) {
                    // Someone else renewed the session while this request was under way.
                    state.sessionStarted()
                    status(401)
                } else {
                    json("[]")
                }
                else -> status(500)
            }
        }
        get(VEHICLES).use { assertEquals(200, it.code) }
        assertEquals(0, count(DEVICE_SESSION))
        assertEquals(2, count(VEHICLES))
    }

    @Test
    fun exchangeRejectedRequiresLogin() {
        setUp { req ->
            when (req.url.encodedPath) {
                VEHICLES -> status(401)
                DEVICE_SESSION -> status(401)
                else -> status(500)
            }
        }
        get(VEHICLES).use { assertEquals(401, it.code) }
        assertEquals(SessionStatus.LOGIN_REQUIRED, state.status.value)
        assertNull(runBlocking { tokens.get() })
        assertEquals(1, count(VEHICLES))
    }

    @Test
    fun unreadableExchangeBodyRequiresLogin() {
        setUp { req ->
            when (req.url.encodedPath) {
                VEHICLES -> status(401)
                // 200, but without the new device token.
                DEVICE_SESSION -> json("""{"user":$USER}""")
                else -> status(500)
            }
        }
        get(VEHICLES).use { assertEquals(401, it.code) }
        assertEquals(SessionStatus.LOGIN_REQUIRED, state.status.value)
        assertNull(runBlocking { tokens.get() })
        assertEquals(1, count(DEVICE_SESSION))
        assertEquals(1, count(VEHICLES))
        assertEquals(0L, state.generation)
    }

    @Test
    fun tokenStoreFailureAfterExchangeRequiresLogin() {
        // Reads the stored token, but cannot store a new one (e.g. Keystore failure).
        val cipher = object : TokenCipher {
            private val readable = FakeTokenCipher()
            override fun encrypt(plain: String): String = throw GeneralSecurityException("keystore unavailable")
            override fun decrypt(encrypted: String): String = readable.decrypt(encrypted)
        }
        setUp(cipher = cipher) { req ->
            when (req.url.encodedPath) {
                VEHICLES -> if (req.hasNewSession()) json("[]") else status(401)
                DEVICE_SESSION -> exchanged()
                else -> status(500)
            }
        }
        // Retrofit suspend call (async in OkHttp): an exception escaping the authenticator would surface here.
        val vehicles = retrofit(client).create(VehiclesApi::class.java)
        assertEquals(401, runBlocking { vehicles.listVehicles() }.code())
        assertEquals(SessionStatus.LOGIN_REQUIRED, state.status.value)
        assertNull(runBlocking { settings.current() }.encryptedDeviceToken)
        assertEquals(1, count(DEVICE_SESSION))
        assertEquals(1, count(VEHICLES))
        assertEquals(0L, state.generation)
    }

    @Test
    fun failedExchangeKeepsTokenAndStatus() {
        setUp { req ->
            when (req.url.encodedPath) {
                VEHICLES -> status(401)
                DEVICE_SESSION -> status(503)
                else -> status(500)
            }
        }
        get(VEHICLES).use { assertEquals(401, it.code) }
        assertEquals(SessionStatus.ACTIVE, state.status.value)
        assertEquals("alt", runBlocking { tokens.get() })
        assertEquals(0L, state.generation)
    }

    @Test
    fun secondUnauthorizedIsNotRetriedAgain() {
        setUp { req ->
            when (req.url.encodedPath) {
                VEHICLES -> status(401)
                DEVICE_SESSION -> exchanged()
                else -> status(500)
            }
        }
        get(VEHICLES).use { assertEquals(401, it.code) }
        assertEquals(1, count(DEVICE_SESSION))
        assertEquals(2, count(VEHICLES))
    }

    @Test
    fun unauthorizedOnDeviceLoginIsNotRetried() {
        setUp { req ->
            when (req.url.encodedPath) {
                DEVICE_LOGIN -> status(401)
                DEVICE_SESSION -> exchanged()
                else -> status(500)
            }
        }
        post(DEVICE_LOGIN).use { assertEquals(401, it.code) }
        assertEquals(0, count(DEVICE_SESSION))
        assertEquals("alt", runBlocking { tokens.get() })
    }

    @Test
    fun missingTokenRequiresLogin() {
        setUp(token = null) { req ->
            when (req.url.encodedPath) {
                VEHICLES -> status(401)
                DEVICE_SESSION -> exchanged()
                else -> status(500)
            }
        }
        get(VEHICLES).use { assertEquals(401, it.code) }
        assertEquals(0, count(DEVICE_SESSION))
        assertEquals(SessionStatus.LOGIN_REQUIRED, state.status.value)
    }

    @Test
    fun householdReselectsProfile() {
        setUp(mode = ServerMode.HOUSEHOLD, token = null, profileId = 3) { req ->
            when (req.url.encodedPath) {
                VEHICLES -> if (req.hasNewSession()) json("[]") else status(401)
                "/api/v1/profiles/3/select" -> MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Set-Cookie", "SESSION=s2; Path=/")
                    .body(USER)
                    .build()
                else -> status(500)
            }
        }
        get(VEHICLES).use { assertEquals(200, it.code) }
        assertEquals(
            listOf("GET $VEHICLES", "POST /api/v1/profiles/3/select", "GET $VEHICLES"),
            seen.toList(),
        )
        assertEquals(SessionStatus.ACTIVE, state.status.value)
        assertEquals(1L, state.generation)
    }

    @Test
    fun deletedProfileRequiresProfileSelection() {
        setUp(mode = ServerMode.HOUSEHOLD, token = null, profileId = 3) { req ->
            when (req.url.encodedPath) {
                VEHICLES -> status(401)
                "/api/v1/profiles/3/select" -> status(404)
                else -> status(500)
            }
        }
        get(VEHICLES).use { assertEquals(401, it.code) }
        assertEquals(SessionStatus.PROFILE_SELECTION_REQUIRED, state.status.value)
        assertNull(runBlocking { settings.current() }.profileId)
    }

    private companion object {
        const val VEHICLES = "/api/v1/vehicles"
        const val DEVICE_SESSION = "/api/v1/auth/device-session"
        const val DEVICE_LOGIN = "/api/v1/auth/device-login"
        const val USER = """{"id":1,"role":"USER","language":"de","currency":"EUR","active":true,
            "emailVerified":true,"createdAt":"2026-01-01T10:00:00Z"}"""
    }
}
