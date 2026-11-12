package de.bikemaintainer.app.ui

import de.bikemaintainer.app.data.repository.AuthRepository
import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.Account
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Profile
import de.bikemaintainer.app.domain.ServerConfig
import de.bikemaintainer.app.domain.ServerMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Configurable [AuthRepository] for ViewModel tests: set the `...Result` of a method, read its `...Calls`.
 * Set a `...Gate` to hold the call in flight until the gate is completed.
 */
class FakeAuthRepository : AuthRepository {
    override val settings = MutableStateFlow(
        StoredSettings(serverUrl = null, mode = null, encryptedDeviceToken = null, profileId = null),
    )

    var fetchConfigResult: Outcome<ServerConfig> = Outcome.Success(ServerConfig(ServerMode.PORTAL, null))
    var fetchConfigGate: CompletableDeferred<Unit>? = null
    val fetchConfigUrls = mutableListOf<String>()
    val fetchConfigCalls get() = fetchConfigUrls.size

    var deviceLoginResult: Outcome<Unit> = Outcome.Success(Unit)
    var deviceLoginGate: CompletableDeferred<Unit>? = null
    val deviceLoginCalls = mutableListOf<List<String>>() // serverUrl, email, password, deviceName

    var registerResult: Outcome<Unit> = Outcome.Success(Unit)
    var registerGate: CompletableDeferred<Unit>? = null
    val registerCalls = mutableListOf<List<Any>>() // serverUrl, email, password, german

    var resendResult: Outcome<Unit> = Outcome.Success(Unit)
    val resendCalls = mutableListOf<List<String>>() // serverUrl, email

    var createProfileResult: Outcome<Profile> = Outcome.Success(Profile(99, "Neu"))
    val createProfileCalls = mutableListOf<String>()

    var profilesResult: Outcome<List<Profile>> = Outcome.Success(emptyList())
    var profilesGate: CompletableDeferred<Unit>? = null
    var profilesCalls = 0

    var selectProfileResult: Outcome<Unit> = Outcome.Success(Unit)
    var selectProfileGate: CompletableDeferred<Unit>? = null
    val selectProfileCalls = mutableListOf<Long>()

    var logoutResult: Outcome<Unit> = Outcome.Success(Unit)
    var logoutGate: CompletableDeferred<Unit>? = null
    var logoutCalls = 0

    var switchServerResult: Outcome<Unit> = Outcome.Success(Unit)
    var switchServerCalls = 0

    var currentAccountResult: Outcome<Account> = Outcome.Success(Account("max@example.org", null))
    var currentAccountCalls = 0

    var markNotificationsAskedCalls = 0

    override suspend fun fetchConfig(serverUrl: String): Outcome<ServerConfig> {
        fetchConfigUrls += serverUrl
        fetchConfigGate?.await()
        return fetchConfigResult
    }

    override suspend fun deviceLogin(
        serverUrl: String,
        email: String,
        password: String,
        deviceName: String,
    ): Outcome<Unit> {
        deviceLoginCalls += listOf(serverUrl, email, password, deviceName)
        deviceLoginGate?.await()
        return deviceLoginResult
    }

    override suspend fun register(serverUrl: String, email: String, password: String, german: Boolean): Outcome<Unit> {
        registerCalls += listOf(serverUrl, email, password, german)
        registerGate?.await()
        return registerResult
    }

    override suspend fun resendVerification(serverUrl: String, email: String): Outcome<Unit> {
        resendCalls += listOf(serverUrl, email)
        return resendResult
    }

    override suspend fun createProfile(serverUrl: String, displayName: String): Outcome<Profile> {
        createProfileCalls += displayName
        return createProfileResult
    }

    override suspend fun profiles(serverUrl: String): Outcome<List<Profile>> {
        profilesCalls++
        profilesGate?.await()
        return profilesResult
    }

    override suspend fun selectProfile(serverUrl: String, id: Long): Outcome<Unit> {
        selectProfileCalls += id
        selectProfileGate?.await()
        return selectProfileResult
    }

    override suspend fun logout(): Outcome<Unit> {
        logoutCalls++
        logoutGate?.await()
        return logoutResult
    }

    override suspend fun switchServer(): Outcome<Unit> {
        switchServerCalls++
        return switchServerResult
    }

    override suspend fun currentAccount(): Outcome<Account> {
        currentAccountCalls++
        return currentAccountResult
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        settings.value = settings.value.copy(notificationsEnabled = enabled)
    }

    override suspend fun markNotificationsAsked() {
        markNotificationsAskedCalls++
    }
}
