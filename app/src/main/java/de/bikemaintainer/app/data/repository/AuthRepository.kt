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

    /**
     * Portal: registers an account (solves the ALTCHA challenge first, which takes a few seconds). Success means
     * "check your inbox" – the server answers the same for a new and an already registered address.
     */
    suspend fun register(serverUrl: String, email: String, password: String, german: Boolean): Outcome<Unit>

    /** Portal: sends the verification email again (also with a solved challenge); same answer for every address. */
    suspend fun resendVerification(serverUrl: String, email: String): Outcome<Unit>

    suspend fun profiles(serverUrl: String): Outcome<List<Profile>>

    /** Household: creates a profile; it still has to be selected. A taken name fails with HTTP 409. */
    suspend fun createProfile(serverUrl: String, displayName: String): Outcome<Profile>

    suspend fun selectProfile(serverUrl: String, id: Long): Outcome<Unit>

    suspend fun logout(): Outcome<Unit>

    suspend fun switchServer(): Outcome<Unit>

    /**
     * Portal: deletes the account of the stored server for good, with all its data; [password] confirms it. Once the
     * server has accepted it, everything stored locally is dropped as with [switchServer], even if that partly fails.
     */
    suspend fun deleteAccount(password: String): Outcome<Unit>

    /** `GET auth/me` on the stored server. */
    suspend fun currentAccount(): Outcome<Account>

    /** Saves the reminder switch; a storage failure is swallowed (the switch then simply keeps its old value). */
    suspend fun setNotificationsEnabled(enabled: Boolean)

    /** Remembers that the notification permission has been requested (it is asked only once). */
    suspend fun markNotificationsAsked()
}
