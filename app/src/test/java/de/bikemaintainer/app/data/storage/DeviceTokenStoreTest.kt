package de.bikemaintainer.app.data.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import javax.crypto.AEADBadTagException
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

    private fun failingWith(error: Exception) = object : TokenCipher {
        override fun encrypt(plain: String): String = "x"
        override fun decrypt(encrypted: String): String = throw error
    }

    @Test
    fun permanentlyUnreadableTokenIsRemoved() = runTest {
        val settings = settings()
        listOf(
            AEADBadTagException("tag"),
            InvalidKeyException("key"),
            UnreadableTokenException("base64"),
            IllegalArgumentException("base64"),
        ).forEach { error ->
            settings.setEncryptedDeviceToken("stored")
            assertNull(DeviceTokenStore(settings, failingWith(error)).get())
            assertNull(error.toString(), settings.current().encryptedDeviceToken)
        }
    }

    @Test
    fun transientKeystoreFailureKeepsToken() = runTest {
        val settings = settings()
        settings.setEncryptedDeviceToken("stored")
        assertNull(DeviceTokenStore(settings, failingWith(GeneralSecurityException("keystore busy"))).get())
        assertEquals("stored", settings.current().encryptedDeviceToken)
    }

    @Test
    fun clearRemovesToken() = runTest {
        val settings = settings()
        val store = DeviceTokenStore(settings, FakeTokenCipher())
        store.set("abc")
        store.clear()
        assertNull(store.get())
    }

    @Test
    fun pendingTokensAreKeptEncryptedNewestFirstAndGoWithTheToken() = runTest {
        val settings = settings()
        val store = DeviceTokenStore(settings, FakeTokenCipher())
        store.set("current")
        (1..5).forEach { store.addPending("p$it") }
        // At most four, the oldest go.
        assertEquals(listOf("p5", "p4", "p3", "p2"), store.pending())
        store.dropPending("p4")
        assertEquals(listOf("p5", "p3", "p2"), store.pending())
        // A new device token replaces them: they belonged to the token it replaces.
        store.set("p3")
        assertEquals(emptyList<String>(), store.pending())
        store.addPending("q")
        settings.clearSession()
        assertEquals(emptyList<String>(), store.pending())
    }
}
