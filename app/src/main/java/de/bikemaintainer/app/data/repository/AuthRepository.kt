package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.Account
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Profile
import de.bikemaintainer.app.domain.ServerConfig
import kotlinx.coroutines.flow.Flow

/** Setup, login, profile selection and account data; implemented by [ApiAuthRepository], faked in UI tests. */
interface AuthRepository {
    /** The stored settings, so the UI does not need the store itself. */
    val settings: Flow<StoredSettings>

    suspend fun fetchConfig(serverUrl: String): Outcome<ServerConfig>

    suspend fun deviceLogin(serverUrl: String, email: String, password: String, deviceName: String): Outcome<Unit>

    suspend fun profiles(serverUrl: String): Outcome<List<Profile>>

    suspend fun selectProfile(serverUrl: String, id: Long): Outcome<Unit>

    suspend fun logout(): Outcome<Unit>

    suspend fun switchServer(): Outcome<Unit>

    /** `GET auth/me` on the stored server. */
    suspend fun currentAccount(): Outcome<Account>

    /** Remembers that the notification permission has been requested (it is asked only once). */
    suspend fun markNotificationsAsked()
}
