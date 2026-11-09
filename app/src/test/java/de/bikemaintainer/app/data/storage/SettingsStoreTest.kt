package de.bikemaintainer.app.data.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.domain.TaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun TestScope.store() = SettingsStore(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { tmpFolder.newFile("t.preferences_pb") },
    )

    @Test
    fun emptyStoreHasNoSettings() = runTest {
        assertEquals(StoredSettings(null, null, null, null), store().current())
    }

    @Test
    fun setServerPersistsUrlAndMode() = runTest {
        val store = store()
        store.setServer("https://bm.example", ServerMode.HOUSEHOLD)
        assertEquals(StoredSettings("https://bm.example", ServerMode.HOUSEHOLD, null, null), store.current())
    }

    @Test
    fun clearSessionKeepsServer() = runTest {
        val store = store()
        store.setServer("https://bm.example", ServerMode.PORTAL)
        store.setEncryptedDeviceToken("tok")
        store.setProfileId(7)
        assertEquals(StoredSettings("https://bm.example", ServerMode.PORTAL, "tok", 7), store.current())
        store.clearSession()
        assertEquals(StoredSettings("https://bm.example", ServerMode.PORTAL, null, null), store.current())
    }

    @Test
    fun portalLoginReplacesHouseholdProfile() = runTest {
        val store = store()
        store.saveHouseholdProfile("http://192.168.0.2:8080/", 7)
        assertEquals(StoredSettings("http://192.168.0.2:8080/", ServerMode.HOUSEHOLD, null, 7), store.current())
        store.savePortalLogin("https://bm.example/", "tok")
        assertEquals(StoredSettings("https://bm.example/", ServerMode.PORTAL, "tok", null), store.current())
        store.saveHouseholdProfile("http://192.168.0.2:8080/", 8)
        assertEquals(StoredSettings("http://192.168.0.2:8080/", ServerMode.HOUSEHOLD, null, 8), store.current())
    }

    @Test
    fun clearAllRemovesEverything() = runTest {
        val store = store()
        store.setServer("https://bm.example", ServerMode.PORTAL)
        store.setEncryptedDeviceToken("tok")
        store.setProfileId(7)
        store.clearAll()
        assertEquals(StoredSettings(null, null, null, null), store.current())
    }

    @Test
    fun notificationsAskedSurvivesClearSession() = runTest {
        val store = store()
        store.setNotificationsAsked()
        store.clearSession()
        assertEquals(true, store.current().notificationsAsked)
    }

    @Test
    fun clearAllResetsNotificationsAsked() = runTest {
        val store = store()
        store.setNotificationsAsked()
        store.clearAll()
        assertEquals(false, store.current().notificationsAsked)
    }

    @Test
    fun reportedDueRoundTrip() = runTest {
        val store = store()
        val map = mapOf(12L to TaskStatus.OVERDUE, 15L to TaskStatus.DUE_SOON)
        store.setReportedDue(map)
        assertEquals(map, store.reportedDue.first())
    }

    @Test
    fun reportedDueEmptyByDefault() = runTest {
        assertEquals(emptyMap<Long, TaskStatus>(), store().reportedDue.first())
    }

    @Test
    fun garbledReportedDueIsSkipped() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { tmpFolder.newFile("g.preferences_pb") }
        dataStore.edit { it[stringPreferencesKey("reported_due")] = "x:Y,7:OVERDUE,8:OK,9" }
        assertEquals(mapOf(7L to TaskStatus.OVERDUE), SettingsStore(dataStore).reportedDue.first())
    }

    @Test
    fun clearSessionDropsReportedDueAndReloginFlagButKeepsEnabled() = runTest {
        val store = store()
        store.setReportedDue(mapOf(1L to TaskStatus.OVERDUE))
        store.setReloginNotified(true)
        store.setNotificationsEnabled(false)
        store.clearSession()
        assertEquals(emptyMap<Long, TaskStatus>(), store.reportedDue.first())
        assertEquals(false, store.current().reloginNotified)
        assertEquals(false, store.current().notificationsEnabled)
    }

    @Test
    fun newLoginResetsReloginFlag() = runTest {
        val store = store()
        store.setReloginNotified(true)
        store.savePortalLogin("https://bm.example/", "tok")
        assertEquals(false, store.current().reloginNotified)
        store.setReloginNotified(true)
        store.saveHouseholdProfile("http://192.168.0.2:8080/", 7)
        assertEquals(false, store.current().reloginNotified)
    }

    @Test
    fun notificationsEnabledDefaultsToTrue() = runTest {
        val store = store()
        assertEquals(true, store.current().notificationsEnabled)
        store.setNotificationsEnabled(false)
        assertEquals(false, store.current().notificationsEnabled)
    }
}
