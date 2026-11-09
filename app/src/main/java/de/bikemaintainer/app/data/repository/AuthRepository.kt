package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.DeviceLoginRequest
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.data.network.SessionState
import de.bikemaintainer.app.data.storage.DeviceTokenStore
import de.bikemaintainer.app.data.storage.SettingsStore
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Profile
import de.bikemaintainer.app.domain.ServerConfig
import de.bikemaintainer.app.domain.ServerMode
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.sync.withLock

/**
 * Login, profile selection and logout. Whatever stores credentials or starts/ends a session does so under
 * [SessionState.lock], the same lock the authenticator holds during a token exchange.
 */
class AuthRepository(
    private val provider: ApiProvider,
    private val settings: SettingsStore,
    private val tokens: DeviceTokenStore,
    private val state: SessionState,
) {
    /** Also sets the CSRF cookie in the jar of this address. */
    suspend fun fetchConfig(serverUrl: String): Outcome<ServerConfig> =
        withApis(serverUrl) { apis -> call { apis.config.getAppConfig() }.map { it.toDomain() } }

    /** Stores server, mode and token only if the login succeeded. */
    suspend fun deviceLogin(serverUrl: String, email: String, password: String, deviceName: String): Outcome<Unit> {
        val base = ApiProvider.normalizeOrNull(serverUrl) ?: return Outcome.Failure(AppError.InvalidAddress)
        val result = call { provider.apisFor(serverUrl).auth.deviceLogin(DeviceLoginRequest(email, password, deviceName)) }
        if (result !is Outcome.Success) return Outcome.Failure((result as Outcome.Failure).error)
        return state.lock.withLock {
            try {
                tokens.set(result.value.deviceToken)
                settings.setServer(base.toString(), ServerMode.PORTAL)
                state.sessionStarted()
                Outcome.Success(Unit)
            } catch (_: GeneralSecurityException) {
                discardPartialLogin()
            } catch (_: IOException) {
                discardPartialLogin()
            }
        }
    }

    suspend fun profiles(serverUrl: String): Outcome<List<Profile>> =
        withApis(serverUrl) { apis -> call { apis.profiles.listProfiles() }.map { list -> list.map { it.toDomain() } } }

    suspend fun selectProfile(serverUrl: String, id: Long): Outcome<Unit> {
        val base = ApiProvider.normalizeOrNull(serverUrl) ?: return Outcome.Failure(AppError.InvalidAddress)
        val result = call { provider.apisFor(serverUrl).profiles.selectProfile(id) }
        if (result is Outcome.Failure) return result
        return state.lock.withLock {
            try {
                settings.setServer(base.toString(), ServerMode.HOUSEHOLD)
                settings.setProfileId(id)
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
    suspend fun logout(): Outcome<Unit> {
        val hasServer = try {
            settings.current().serverUrl != null
        } catch (_: IOException) {
            false
        }
        val post = if (hasServer) call { provider.apis().auth.logout() } else Outcome.Success(Unit)
        return state.lock.withLock {
            try {
                settings.clearSession()
                post
            } catch (_: IOException) {
                Outcome.Failure(AppError.Storage)
            } finally {
                provider.reset()
            }
        }
    }

    suspend fun switchServer(): Outcome<Unit> {
        logout()
        return state.lock.withLock {
            try {
                settings.clearAll()
                Outcome.Success(Unit)
            } catch (_: IOException) {
                Outcome.Failure(AppError.Storage)
            } finally {
                provider.reset()
            }
        }
    }

    private suspend fun <T> withApis(serverUrl: String, block: suspend (ApiProvider.Apis) -> Outcome<T>): Outcome<T> {
        if (ApiProvider.normalizeOrNull(serverUrl) == null) return Outcome.Failure(AppError.InvalidAddress)
        return block(provider.apisFor(serverUrl))
    }

    /** Best effort: no token without a stored server. */
    private suspend fun discardPartialLogin(): Outcome<Unit> {
        try {
            settings.clearSession()
        } catch (_: IOException) {
            // Storage is broken anyway; the caller is told so.
        }
        return Outcome.Failure(AppError.Storage)
    }
}
