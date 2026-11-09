package de.bikemaintainer.app.data.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import de.bikemaintainer.app.domain.ServerMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class StoredSettings(
    val serverUrl: String?,
    val mode: ServerMode?,
    val encryptedDeviceToken: String?,
    val profileId: Long?,
)

class SettingsStore(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<StoredSettings> = dataStore.data.map { prefs ->
        StoredSettings(
            serverUrl = prefs[SERVER_URL],
            mode = prefs[SERVER_MODE]?.let { name -> ServerMode.entries.firstOrNull { it.name == name } },
            encryptedDeviceToken = prefs[DEVICE_TOKEN_ENC],
            profileId = prefs[PROFILE_ID],
        )
    }

    suspend fun current(): StoredSettings = settings.first()

    suspend fun setServer(url: String, mode: ServerMode) {
        dataStore.edit {
            it[SERVER_URL] = url
            it[SERVER_MODE] = mode.name
        }
    }

    /**
     * A portal login in one write: server, mode and (encrypted) device token; a household profile id is removed.
     * Use [DeviceTokenStore.saveLogin], which encrypts the token.
     */
    suspend fun savePortalLogin(url: String, encryptedDeviceToken: String) {
        dataStore.edit {
            it[SERVER_URL] = url
            it[SERVER_MODE] = ServerMode.PORTAL.name
            it[DEVICE_TOKEN_ENC] = encryptedDeviceToken
            it.remove(PROFILE_ID)
        }
    }

    /** A household profile selection in one write: server, mode and profile id; a device token is removed. */
    suspend fun saveHouseholdProfile(url: String, profileId: Long) {
        dataStore.edit {
            it[SERVER_URL] = url
            it[SERVER_MODE] = ServerMode.HOUSEHOLD.name
            it[PROFILE_ID] = profileId
            it.remove(DEVICE_TOKEN_ENC)
        }
    }

    suspend fun setEncryptedDeviceToken(value: String?) {
        dataStore.edit { if (value == null) it.remove(DEVICE_TOKEN_ENC) else it[DEVICE_TOKEN_ENC] = value }
    }

    suspend fun setProfileId(id: Long?) {
        dataStore.edit { if (id == null) it.remove(PROFILE_ID) else it[PROFILE_ID] = id }
    }

    /** Forgets token and profile, keeps the server. */
    suspend fun clearSession() {
        dataStore.edit {
            it.remove(DEVICE_TOKEN_ENC)
            it.remove(PROFILE_ID)
        }
    }

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val SERVER_MODE = stringPreferencesKey("server_mode")
        val DEVICE_TOKEN_ENC = stringPreferencesKey("device_token_enc")
        val PROFILE_ID = longPreferencesKey("profile_id")
    }
}
