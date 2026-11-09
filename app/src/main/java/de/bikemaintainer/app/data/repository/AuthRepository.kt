package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.DeviceLoginRequest
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.data.network.SessionState
import de.bikemaintainer.app.data.storage.DeviceTokenStore
import de.bikemaintainer.app.data.storage.SettingsStore
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Profile
import de.bikemaintainer.app.domain.ServerConfig
import de.bikemaintainer.app.domain.ServerMode
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
        call { provider.apisFor(serverUrl).config.getAppConfig() }.map { it.toDomain() }

    /** Stores server, mode and token only if the login succeeded. */
    suspend fun deviceLogin(serverUrl: String, email: String, password: String, deviceName: String): Outcome<Unit> {
        val result = call {
            provider.apisFor(serverUrl).auth.deviceLogin(DeviceLoginRequest(email, password, deviceName))
        }
        if (result !is Outcome.Success) return Outcome.Failure((result as Outcome.Failure).error)
        state.lock.withLock {
            tokens.set(result.value.deviceToken)
            settings.setServer(ApiProvider.normalize(serverUrl).toString(), ServerMode.PORTAL)
            state.sessionStarted()
        }
        return Outcome.Success(Unit)
    }

    suspend fun profiles(serverUrl: String): Outcome<List<Profile>> =
        call { provider.apisFor(serverUrl).profiles.listProfiles() }.map { list -> list.map { it.toDomain() } }

    suspend fun selectProfile(serverUrl: String, id: Long): Outcome<Unit> {
        val result = call { provider.apisFor(serverUrl).profiles.selectProfile(id) }
        if (result is Outcome.Failure) return result
        state.lock.withLock {
            settings.setServer(ApiProvider.normalize(serverUrl).toString(), ServerMode.HOUSEHOLD)
            settings.setProfileId(id)
            state.sessionStarted()
        }
        return Outcome.Success(Unit)
    }

    /** The local session is dropped even if the server cannot be reached; the result is that of the POST. */
    suspend fun logout(): Outcome<Unit> {
        val result = if (settings.current().serverUrl == null) {
            Outcome.Success(Unit)
        } else {
            call { provider.apis().auth.logout() }
        }
        state.lock.withLock {
            settings.clearSession()
            provider.reset()
        }
        return result
    }

    suspend fun switchServer() {
        logout()
        state.lock.withLock {
            settings.clearAll()
            provider.reset()
        }
    }
}
