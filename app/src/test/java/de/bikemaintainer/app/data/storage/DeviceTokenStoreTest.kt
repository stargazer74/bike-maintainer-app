package de.bikemaintainer.app.data.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceTokenStoreTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun TestScope.settings() = SettingsStore(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { tmpFolder.newFile("t.preferences_pb") },
    )

    @Test
    fun tokenIsStoredEncrypted() = runTest {
        val settings = settings()
        val store = DeviceTokenStore(settings, FakeTokenCipher())
        store.set("abc")
        assertEquals("enc:abc", settings.current().encryptedDeviceToken)
        assertEquals("abc", store.get())
    }

    @Test
    fun undecryptableTokenMeansNoToken() = runTest {
        val settings = settings()
        settings.setEncryptedDeviceToken("garbage")
        val store = DeviceTokenStore(settings, FakeTokenCipher())
        assertNull(store.get())
        assertNull(settings.current().encryptedDeviceToken)
    }

    @Test
    fun clearRemovesToken() = runTest {
        val settings = settings()
        val store = DeviceTokenStore(settings, FakeTokenCipher())
        store.set("abc")
        store.clear()
        assertNull(store.get())
    }
}
