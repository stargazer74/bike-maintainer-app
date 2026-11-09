package de.bikemaintainer.app.data.network

import de.bikemaintainer.app.api.apis.AuthApi
import de.bikemaintainer.app.api.apis.ConfigApi
import de.bikemaintainer.app.api.apis.MaintenanceLogsApi
import de.bikemaintainer.app.api.apis.MaintenanceTasksApi
import de.bikemaintainer.app.api.apis.ProfilesApi
import de.bikemaintainer.app.api.apis.VehiclesApi
import de.bikemaintainer.app.api.infrastructure.Serializer
import de.bikemaintainer.app.data.storage.DeviceTokenStore
import de.bikemaintainer.app.data.storage.SettingsStore
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Builds and caches the API clients per (normalised) server address. Each address gets its own cookie jar, session
 * authenticator, app client and one session client for token exchanges. [reset] drops all of it, including cookies.
 */
class ApiProvider(
    private val settings: SettingsStore,
    private val tokens: DeviceTokenStore,
    private val state: SessionState,
    private val debugLog: ((String) -> Unit)?,
) {
    data class Apis(
        val auth: AuthApi,
        val config: ConfigApi,
        val profiles: ProfilesApi,
        val vehicles: VehiclesApi,
        val tasks: MaintenanceTasksApi,
        val logs: MaintenanceLogsApi,
    )

    /** The APIs of one address with the cookie jar they share. */
    private class Entry(val apis: Apis, val cookieJar: InMemoryCookieJar)

    private val cache = HashMap<String, Entry>()

    fun apisFor(serverUrl: String): Apis = entryFor(normalize(serverUrl)).apis

    /**
     * Whether the cookie jar of this address holds the CSRF cookie (XSRF-TOKEN). Without it a state-changing request
     * is rejected by the server; any GET, e.g. the config, sets it.
     */
    fun hasCsrfCookie(serverUrl: String): Boolean {
        val base = normalize(serverUrl)
        return entryFor(base).cookieJar.valueOf(base, CsrfInterceptor.COOKIE_NAME) != null
    }

    /**
     * The APIs of the stored server address, or null if none (or no valid one) is stored.
     * @throws java.io.IOException if the settings cannot be read.
     */
    suspend fun apis(): Apis? {
        val base = settings.current().serverUrl?.let(::normalizeOrNull) ?: return null
        return entryFor(base).apis
    }

    private fun entryFor(base: HttpUrl): Entry = synchronized(cache) { cache.getOrPut(base.toString()) { build(base) } }

    fun reset() {
        synchronized(cache) { cache.clear() }
    }

    private fun build(base: HttpUrl): Entry {
        val cookieJar = InMemoryCookieJar()
        // The session client is built from the main client, which in turn needs the authenticator: hence the lazies.
        lateinit var client: OkHttpClient
        val sessionRetrofit by lazy { retrofit(SessionAuthenticator.sessionClient(client), base) }
        val authenticator = SessionAuthenticator(base, settings, tokens, state) {
            sessionRetrofit.create(AuthApi::class.java) to sessionRetrofit.create(ProfilesApi::class.java)
        }
        client = HttpClientFactory.create(cookieJar, authenticator, listOf(GenerationTagInterceptor(state)), debugLog)
        val retrofit = retrofit(client, base)
        val apis = Apis(
            auth = retrofit.create(AuthApi::class.java),
            config = retrofit.create(ConfigApi::class.java),
            profiles = retrofit.create(ProfilesApi::class.java),
            vehicles = retrofit.create(VehiclesApi::class.java),
            tasks = retrofit.create(MaintenanceTasksApi::class.java),
            logs = retrofit.create(MaintenanceLogsApi::class.java),
        )
        return Entry(apis, cookieJar)
    }

    private fun retrofit(client: OkHttpClient, base: HttpUrl): Retrofit = Retrofit.Builder()
        .baseUrl(base)
        .client(client)
        .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
        .build()

    companion object {
        /** Trimmed, with exactly one trailing slash. Throws [IllegalArgumentException] for an invalid address. */
        fun normalize(url: String): HttpUrl = (url.trim().trimEnd('/') + "/").toHttpUrl()

        /** Like [normalize], but null for an invalid address. */
        fun normalizeOrNull(url: String): HttpUrl? = (url.trim().trimEnd('/') + "/").toHttpUrlOrNull()
    }
}
