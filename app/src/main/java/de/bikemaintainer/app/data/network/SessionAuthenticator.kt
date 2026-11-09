package de.bikemaintainer.app.data.network

import de.bikemaintainer.app.api.apis.AuthApi
import de.bikemaintainer.app.api.apis.ProfilesApi
import de.bikemaintainer.app.api.models.DeviceSessionRequest
import de.bikemaintainer.app.data.storage.DeviceTokenStore
import de.bikemaintainer.app.data.storage.SettingsStore
import de.bikemaintainer.app.domain.ServerMode
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import okhttp3.Authenticator
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Renews an expired server session on a 401: in portal mode by exchanging the device token for a session (and a new
 * token), in household mode by selecting the stored profile again; then the request is retried once.
 *
 * The device token is single-use: the server treats a reused token as copied and removes the device. Hence the
 * exchange is serialised, happens at most once per session generation, and the new token is stored before anything
 * else may use the new session.
 *
 * [sessionApis] must call through a client without this authenticator (see [sessionClient]).
 */
class SessionAuthenticator(
    private val settings: SettingsStore,
    private val tokens: DeviceTokenStore,
    private val state: SessionState,
    private val sessionApis: () -> Pair<AuthApi, ProfilesApi>,
) : Authenticator {
    // OkHttp calls this on its own (blocking) thread, never on one holding the lock.
    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        val path = request.url.encodedPath
        if (EXCLUDED_SUFFIXES.any { path.endsWith(it) }) return null
        if (response.priorResponse != null) return null
        // Untagged requests (should not exist) count as sent in the current generation.
        val sentGeneration = request.tag(SessionGeneration::class)?.value ?: state.generation
        return runBlocking {
            state.lock.withLock {
                if (state.generation != sentGeneration) {
                    // Someone else has renewed the session meanwhile: just retry, no second exchange.
                    retry(request)
                } else {
                    // Nothing but IOException may leave an authenticator: for async calls OkHttp would rethrow anything
                    // else on its dispatcher thread and crash the app. A failure here just means "no renewal".
                    try {
                        renew(request)
                    } catch (_: IOException) {
                        null
                    } catch (_: GeneralSecurityException) {
                        null
                    } catch (_: RuntimeException) {
                        null
                    }
                }
            }
        }
    }

    private suspend fun renew(request: Request): Request? = when (settings.current().mode) {
        ServerMode.PORTAL -> exchangeDeviceToken(request)
        ServerMode.HOUSEHOLD -> reselectProfile(request)
        null -> null
    }

    private suspend fun exchangeDeviceToken(request: Request): Request? {
        val token = tokens.get()
        if (token == null) {
            state.require(SessionStatus.LOGIN_REQUIRED)
            return null
        }
        val result = try {
            sessionApis().first.createDeviceSession(DeviceSessionRequest(token))
        } catch (_: SerializationException) {
            // A 200 whose body cannot be read: the server has already rotated the token, but we never got the new one.
            return rejectExchange()
        }
        if (result.code() == HTTP_UNAUTHORIZED) return rejectExchange()
        if (result.code() != HTTP_OK) return null
        // Same as above: the old token is used up; presenting it again would look like a copied token.
        val newToken = result.body()?.deviceToken ?: return rejectExchange()
        // Store the new token before the new session can be used. If that fails, the old one is used up all the same.
        try {
            tokens.set(newToken)
        } catch (_: GeneralSecurityException) {
            return rejectExchange()
        } catch (_: RuntimeException) {
            return rejectExchange()
        }
        state.sessionStarted()
        return retry(request)
    }

    /** The device token is invalid or used up: forget it, the user has to log in again. */
    private suspend fun rejectExchange(): Request? {
        // Status first: should clearing the token fail as well, the user is still sent to the login.
        state.require(SessionStatus.LOGIN_REQUIRED)
        tokens.clear()
        return null
    }

    private suspend fun reselectProfile(request: Request): Request? {
        val profileId = settings.current().profileId
        if (profileId == null) {
            state.require(SessionStatus.PROFILE_SELECTION_REQUIRED)
            return null
        }
        val result = sessionApis().second.selectProfile(profileId)
        return when (result.code()) {
            HTTP_OK -> {
                state.sessionStarted()
                retry(request)
            }
            HTTP_NOT_FOUND -> {
                settings.setProfileId(null)
                state.require(SessionStatus.PROFILE_SELECTION_REQUIRED)
                null
            }
            else -> null
        }
    }

    /** Same request in the current generation; the CSRF header is set again by the network interceptor. */
    private fun retry(request: Request): Request =
        request.newBuilder().tag(SessionGeneration::class, SessionGeneration(state.generation)).build()

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404

        /** A 401 on these is an answer, not an expired session; renewing on `/select` or `/config` makes no sense. */
        private val EXCLUDED_SUFFIXES = listOf("/auth/device-session", "/auth/device-login", "/select", "/config")

        /**
         * The client for [sessionApis]: same cookie jar and network interceptors, but no authenticator (no renewal
         * of the renewal) and its own dispatcher. The calls waiting in [authenticate] keep their slots of the app
         * client's dispatcher (at most 5 per host); with a shared dispatcher, 5 parallel 401s would deadlock.
         */
        fun sessionClient(client: OkHttpClient): OkHttpClient =
            client.newBuilder().authenticator(Authenticator.NONE).dispatcher(Dispatcher()).build()
    }
}
