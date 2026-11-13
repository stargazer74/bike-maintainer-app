package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.DeleteAccountRequest
import de.bikemaintainer.app.api.models.DeviceLoginRequest
import de.bikemaintainer.app.api.models.EmailRequest
import de.bikemaintainer.app.api.models.Language
import de.bikemaintainer.app.api.models.ProfileRequest
import de.bikemaintainer.app.api.models.RegisterRequest
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.data.network.SessionState
import de.bikemaintainer.app.data.storage.DeviceTokenStore
import de.bikemaintainer.app.data.storage.OfflineStore
import de.bikemaintainer.app.data.storage.SettingsStore
import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.Access
import de.bikemaintainer.app.domain.AccessStatus
import de.bikemaintainer.app.domain.Account
import de.bikemaintainer.app.domain.AltchaSolver
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Profile
import de.bikemaintainer.app.domain.ServerConfig
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Login, profile selection and logout. Whatever stores credentials or starts/ends a session does so under
 * [SessionState.lock], the same lock the authenticator holds during a token exchange.
 *
 * The data stored for offline use ([offline]) belongs to one person on one server: it is cleared on every login and
 * profile choice (someone else may sign in) and on logout, server switch and account deletion.
 */
class ApiAuthRepository(
    private val provider: ApiProvider,
    private val store: SettingsStore,
    private val tokens: DeviceTokenStore,
    private val state: SessionState,
    private val offline: OfflineStore = OfflineStore.None,
) : AuthRepository {
    override val settings: Flow<StoredSettings> = store.settings

    private val _access = MutableStateFlow(Access.FULL)
    override val access: StateFlow<Access> = _access.asStateFlow()

    /** Counts logouts: an answer of `auth/me` from before one must not bring back the access of the old account. */
    private val session = AtomicInteger()

    override suspend fun refreshAccess() {
        currentAccount()
    }

    override fun subscriptionRequired() = _access.update { it.copy(status = AccessStatus.EXPIRED) }

    /** Also sets the CSRF cookie in the jar of this address. */
    override suspend fun fetchConfig(serverUrl: String): Outcome<ServerConfig> =
        withApis(serverUrl) { apis -> call { apis.config.getAppConfig() }.map { it.toDomain() } }

    /** Stores server, mode and token (in one write) only if the login succeeded. */
    override suspend fun deviceLogin(serverUrl: String, email: String, password: String, deviceName: String): Outcome<Unit> {
        val base = ApiProvider.normalizeOrNull(serverUrl) ?: return Outcome.Failure(AppError.InvalidAddress)
        ensureCsrfCookie(serverUrl)?.let { return it }
        val result = call { provider.apisFor(serverUrl).auth.deviceLogin(DeviceLoginRequest(email, password, deviceName)) }
        if (result !is Outcome.Success) return Outcome.Failure((result as Outcome.Failure).error)
        return state.lock.withLock {
            // One write: on failure nothing has changed (in particular no new token next to an old server).
            try {
                offline.clear()
                tokens.saveLogin(base.toString(), result.value.deviceToken)
                state.sessionStarted()
                Outcome.Success(Unit)
            } catch (_: GeneralSecurityException) {
                Outcome.Failure(AppError.Storage)
            } catch (_: IOException) {
                Outcome.Failure(AppError.Storage)
            }
        }
    }

    /** The honeypot field `website` is left out: the app has no bots to catch. */
    override suspend fun register(serverUrl: String, email: String, password: String, german: Boolean): Outcome<Unit> =
        withAltcha(serverUrl) { apis, altcha ->
            val language = if (german) Language.DE else Language.EN
            callNoBody { apis.auth.register(RegisterRequest(email, password, altcha, language)) }
        }

    override suspend fun resendVerification(serverUrl: String, email: String): Outcome<Unit> =
        withAltcha(serverUrl) { apis, altcha -> callNoBody { apis.auth.resendVerificationEmail(EmailRequest(email, altcha)) } }

    override suspend fun profiles(serverUrl: String): Outcome<List<Profile>> =
        withApis(serverUrl) { apis -> call { apis.profiles.listProfiles() }.map { list -> list.map { it.toDomain() } } }

    override suspend fun createProfile(serverUrl: String, displayName: String): Outcome<Profile> {
        if (ApiProvider.normalizeOrNull(serverUrl) == null) return Outcome.Failure(AppError.InvalidAddress)
        ensureCsrfCookie(serverUrl)?.let { return it }
        return call { provider.apisFor(serverUrl).profiles.createProfile(ProfileRequest(displayName)) }.map { it.toDomain() }
    }

    override suspend fun selectProfile(serverUrl: String, id: Long): Outcome<Unit> {
        val base = ApiProvider.normalizeOrNull(serverUrl) ?: return Outcome.Failure(AppError.InvalidAddress)
        ensureCsrfCookie(serverUrl)?.let { return it }
        val result = call { provider.apisFor(serverUrl).profiles.selectProfile(id) }
        if (result is Outcome.Failure) return result
        return state.lock.withLock {
            // One write: a failure must not leave the previous person's profile id next to the new server.
            try {
                offline.clear()
                store.saveHouseholdProfile(base.toString(), id)
                state.sessionStarted()
                Outcome.Success(Unit)
            } catch (_: IOException) {
                Outcome.Failure(AppError.Storage)
            }
        }
    }

    /**
     * The local session is dropped even if the server cannot be reached or storage fails (the provider, and with it
     * the cookies, is always reset). The result is that of the POST, or Storage if clearing failed.
     */
    override suspend fun logout(): Outcome<Unit> {
        val hasServer = try {
            store.current().serverUrl != null
        } catch (_: IOException) {
            false
        }
        val post = if (hasServer) provider.callStored { it.auth.logout() } else Outcome.Success(Unit)
        return state.lock.withLock {
            try {
                store.clearSession()
                post
            } catch (_: IOException) {
                Outcome.Failure(AppError.Storage)
            } finally {
                offline.clear()
                provider.reset()
                session.incrementAndGet()
                _access.value = Access.FULL
            }
        }
    }

    override suspend fun switchServer(): Outcome<Unit> {
        logout()
        return state.lock.withLock {
            try {
                store.clearAll()
                Outcome.Success(Unit)
            } catch (_: IOException) {
                Outcome.Failure(AppError.Storage)
            } finally {
                provider.reset()
            }
        }
    }

    /** The server also ends all sessions and device tokens of the account, so there is nothing to log out from. */
    override suspend fun deleteAccount(password: String): Outcome<Unit> {
        val server = try {
            store.current().serverUrl
        } catch (_: IOException) {
            return Outcome.Failure(AppError.Storage)
        } ?: return Outcome.Failure(AppError.NotConfigured)
        if (ApiProvider.normalizeOrNull(server) == null) return Outcome.Failure(AppError.NotConfigured)
        ensureCsrfCookie(server)?.let { return it }
        val result = callNoBody { provider.apisFor(server).account.deleteCurrentUser(DeleteAccountRequest(password)) }
        if (result is Outcome.Failure) return result
        state.lock.withLock {
            try {
                store.clearAll()
            } catch (_: IOException) {
                // intentionally ignored: the account and its token are gone on the server anyway
            } finally {
                offline.clear()
                provider.reset()
                session.incrementAndGet()
                _access.value = Access.FULL
            }
        }
        return Outcome.Success(Unit)
    }

    override suspend fun currentAccount(): Outcome<Account> {
        val asked = session.get()
        val result = provider.callStored { it.auth.getCurrentUser() }
            .map { Account(it.email, it.displayName, Access.of(it.accessStatus.value, it.accessUntil, it.hasSubscription)) }
        if (result is Outcome.Success) {
            _access.update { old -> if (session.get() == asked) result.value.access else old }
        }
        return result
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        try {
            store.setNotificationsEnabled(enabled)
        } catch (_: IOException) {
            // intentionally ignored
        }
    }

    /** A storage failure is swallowed: worst case the question is asked again next time. */
    override suspend fun markNotificationsAsked() {
        try {
            store.setNotificationsAsked()
        } catch (_: IOException) {
            // intentionally ignored
        }
    }

    /**
     * The backend rejects a POST without the CSRF cookie/header pair. Login and profile selection can be the first
     * request to an address (e.g. after logout, which drops all cookies), so the cookie is fetched via GET /config
     * first if missing. Returns the failure of that GET, or null if the cookie is there.
     */
    private suspend fun ensureCsrfCookie(serverUrl: String): Outcome.Failure? {
        if (provider.hasCsrfCookie(serverUrl)) return null
        return call { provider.apisFor(serverUrl).config.getAppConfig() } as? Outcome.Failure
    }

    /**
     * Fetches a fresh ALTCHA challenge, solves it off the main thread and runs [block] with the payload. A challenge the
     * solver cannot handle counts as an unreadable response (status -1).
     */
    private suspend fun withAltcha(
        serverUrl: String,
        block: suspend (ApiProvider.Apis, String) -> Outcome<Unit>,
    ): Outcome<Unit> {
        if (ApiProvider.normalizeOrNull(serverUrl) == null) return Outcome.Failure(AppError.InvalidAddress)
        ensureCsrfCookie(serverUrl)?.let { return it }
        val apis = provider.apisFor(serverUrl)
        val challenge = when (val result = call { apis.challenge.challenge() }) {
            is Outcome.Failure -> return result
            is Outcome.Success -> try {
                result.value.use { it.string() }
            } catch (_: IOException) {
                return Outcome.Failure(AppError.Network)
            }
        }
        val altcha = try {
            withContext(Dispatchers.Default) { AltchaSolver.solve(challenge) }
        } catch (_: AltchaSolver.InvalidChallengeException) {
            return Outcome.Failure(AppError.Http(-1, null))
        }
        return block(apis, altcha)
    }

    private suspend fun <T> withApis(serverUrl: String, block: suspend (ApiProvider.Apis) -> Outcome<T>): Outcome<T> {
        if (ApiProvider.normalizeOrNull(serverUrl) == null) return Outcome.Failure(AppError.InvalidAddress)
        return block(provider.apisFor(serverUrl))
    }
}
