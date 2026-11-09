package de.bikemaintainer.app.data.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import de.bikemaintainer.app.domain.ServerMode
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
    fun clearAllRemovesEverything() = runTest {
        val store = store()
        store.setServer("https://bm.example", ServerMode.PORTAL)
        store.setEncryptedDeviceToken("tok")
        store.setProfileId(7)
        store.clearAll()
        assertEquals(StoredSettings(null, null, null, null), store.current())
    }
}
