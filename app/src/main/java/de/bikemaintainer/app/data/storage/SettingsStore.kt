package de.bikemaintainer.app.data.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.domain.TaskStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class StoredSettings(
    val serverUrl: String?,
    val mode: ServerMode?,
    val encryptedDeviceToken: String?,
    val profileId: Long?,
    val notificationsAsked: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val reloginNotified: Boolean = false,
)

class SettingsStore(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<StoredSettings> = dataStore.data.map { prefs ->
        StoredSettings(
            serverUrl = prefs[SERVER_URL],
            mode = prefs[SERVER_MODE]?.let { name -> ServerMode.entries.firstOrNull { it.name == name } },
            encryptedDeviceToken = prefs[DEVICE_TOKEN_ENC],
            profileId = prefs[PROFILE_ID],
            notificationsAsked = prefs[NOTIFICATIONS_ASKED] ?: false,
            notificationsEnabled = prefs[NOTIFICATIONS_ENABLED] ?: true,
            reloginNotified = prefs[RELOGIN_NOTIFIED] ?: false,
        )
    }

    /** Due tasks already reported (task id to status), see `dueUpdates`. */
    val reportedDue: Flow<Map<Long, TaskStatus>> = dataStore.data.map { prefs -> decodeReportedDue(prefs[REPORTED_DUE]) }

    suspend fun setReportedDue(map: Map<Long, TaskStatus>) {
        dataStore.edit { it[REPORTED_DUE] = map.entries.joinToString(",") { (id, status) -> "$id:${status.name}" } }
    }

    /** The user's wish; survives [clearSession]. */
    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setReloginNotified(value: Boolean) {
        dataStore.edit { it[RELOGIN_NOTIFIED] = value }
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
            it.remove(RELOGIN_NOTIFIED)
        }
    }

    /** A household profile selection in one write: server, mode and profile id; a device token is removed. */
    suspend fun saveHouseholdProfile(url: String, profileId: Long) {
        dataStore.edit {
            it[SERVER_URL] = url
            it[SERVER_MODE] = ServerMode.HOUSEHOLD.name
            it[PROFILE_ID] = profileId
            it.remove(DEVICE_TOKEN_ENC)
            it.remove(RELOGIN_NOTIFIED)
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
            it.remove(REPORTED_DUE)
            it.remove(RELOGIN_NOTIFIED)
        }
    }

    /** Survives [clearSession] (it belongs to the app, not the session); [clearAll] resets it. */
    suspend fun setNotificationsAsked() {
        dataStore.edit { it[NOTIFICATIONS_ASKED] = true }
    }

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val REPORTED_DUE = stringPreferencesKey("reported_due")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val RELOGIN_NOTIFIED = booleanPreferencesKey("relogin_notified")

        /** Parses `"12:OVERDUE,15:DUE_SOON"`; unreadable entries are skipped. */
        fun decodeReportedDue(raw: String?): Map<Long, TaskStatus> =
            raw.orEmpty().split(',').mapNotNull { entry ->
                val (id, name) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val status = TaskStatus.entries.firstOrNull { it.name == name }
                if (id.toLongOrNull() == null || status == null || status !in DUE_STATES) null else id.toLong() to status
            }.toMap()

        val DUE_STATES = setOf(TaskStatus.OVERDUE, TaskStatus.DUE_SOON)
        val SERVER_URL = stringPreferencesKey("server_url")
        val SERVER_MODE = stringPreferencesKey("server_mode")
        val DEVICE_TOKEN_ENC = stringPreferencesKey("device_token_enc")
        val PROFILE_ID = longPreferencesKey("profile_id")
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("notifications_asked")
    }
}
