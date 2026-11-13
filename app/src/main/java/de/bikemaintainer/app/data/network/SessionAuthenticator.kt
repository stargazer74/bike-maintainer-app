package de.bikemaintainer.app.data.network

import de.bikemaintainer.app.api.apis.AuthApi
import de.bikemaintainer.app.api.apis.ProfilesApi
import de.bikemaintainer.app.api.models.DeviceSessionRequest
import de.bikemaintainer.app.data.storage.DeviceTokenStore
import de.bikemaintainer.app.data.storage.SettingsStore
import de.bikemaintainer.app.domain.ServerMode
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import okhttp3.Authenticator
import okhttp3.Dispatcher
import okhttp3.HttpUrl
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
 * Lost answers (backend#164): the app chooses the next token itself (`nextDeviceToken`) and stores it as pending
 * before sending. If the exchange reaches the server but its answer is lost, the next renewal presents the pending
 * token first – the server's current one – and only after a 401 the stored token. A dropped connection therefore no
 * longer costs the login.
 *
 * Renewal only happens for requests to [baseUrl] when it is the stored server address (normalised as in
 * [ApiProvider.normalize]); token and profile are never sent anywhere else.
 *
 * [sessionApis] must call through a client without this authenticator (see [sessionClient]).
 */
class SessionAuthenticator(
    private val baseUrl: HttpUrl,
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

    private suspend fun renew(request: Request): Request? {
        val stored = settings.current()
        // Token and profile belong to the stored server. A client for any other address (a candidate during setup or
        // "switch server") must never send them there: a hostile server would get a valid device token.
        val storedBase = stored.serverUrl?.let(ApiProvider::normalizeOrNull)
        if (storedBase != baseUrl) return null
        return when (stored.mode) {
            ServerMode.PORTAL -> exchangeDeviceToken(request)
            ServerMode.HOUSEHOLD -> reselectProfile(request)
            null -> null
        }
    }

    private suspend fun exchangeDeviceToken(request: Request): Request? {
        // After a rejected exchange the used-up token may still be stored (clearing it can fail as well): never present
        // it again. Only a new login (which starts a new session) leaves this state.
        if (state.status.value == SessionStatus.LOGIN_REQUIRED) return null
        val token = tokens.get()
        if (token == null) {
            // Still stored means it could not be decrypted right now (transient Keystore error): no renewal this time,
            // but the token stays valid. Gone means there is none (or it was unreadable for good): log in again.
            if (settings.current().encryptedDeviceToken == null) state.require(SessionStatus.LOGIN_REQUIRED)
            return null
        }
        // Tokens proposed in an exchange whose answer was lost come first, newest first: one of them may be the
        // server's current token. One the server does not know (the request never arrived) is dropped.
        for (candidate in tokens.pending()) {
            when (val outcome = exchange(candidate)) {
                Exchange.Rejected -> tokens.dropPending(candidate)
                else -> return finish(outcome, request)
            }
        }
        return finish(exchange(token), request)
    }

    private sealed interface Exchange {
        /** A new session runs; [token] is the new device token. */
        data class Exchanged(val token: String) : Exchange

        /** 401: the presented token is unknown, used up or the account cannot log in. */
        data object Rejected : Exchange

        /** Any other answer: no renewal this time, nothing changes. */
        data object Failed : Exchange
    }

    /**
     * Presents [presented] together with a new token the app chose (`nextDeviceToken`, backend#164). The new token is
     * stored as pending **before** the request is sent: if the answer is lost, the app still knows the token the server
     * now has. An IOException leaves everything as it is.
     */
    private suspend fun exchange(presented: String): Exchange {
        val next = newDeviceToken()
        tokens.addPending(next)
        val result = try {
            sessionApis().first.createDeviceSession(DeviceSessionRequest(deviceToken = presented, nextDeviceToken = next))
        } catch (_: SerializationException) {
            // A 200 whose body cannot be read: the server has taken the token we chose.
            return Exchange.Exchanged(next)
        }
        if (result.code() == HTTP_UNAUTHORIZED) {
            tokens.dropPending(next)
            return Exchange.Rejected
        }
        if (result.code() != HTTP_OK) return Exchange.Failed
        // A server that predates nextDeviceToken generates its own token; a current one returns ours.
        return Exchange.Exchanged(result.body()?.deviceToken ?: next)
    }

    private suspend fun finish(outcome: Exchange, request: Request): Request? = when (outcome) {
        Exchange.Rejected -> rejectExchange()
        Exchange.Failed -> null
        is Exchange.Exchanged -> {
            // Store the new token before the new session can be used. If that fails, the token is still among the
            // pending ones when the app chose it, and the next renewal finds it; one the server generated is lost.
            val stored = try {
                tokens.set(outcome.token)
                true
            } catch (_: IOException) {
                false
            } catch (_: GeneralSecurityException) {
                false
            } catch (_: RuntimeException) {
                false
            }
            if (!stored && outcome.token !in tokens.pending()) {
                rejectExchange()
            } else {
                state.sessionStarted()
                retry(request)
            }
        }
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

    /** 256 random bits as Base64url without padding (43 characters), like the server's tokens. */
    private fun newDeviceToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private val random = SecureRandom()

    companion object {
        private const val HTTP_OK = 200
        private const val TOKEN_BYTES = 32
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
