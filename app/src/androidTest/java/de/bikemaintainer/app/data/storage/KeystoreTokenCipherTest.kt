package de.bikemaintainer.app.data.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreTokenCipherTest {
    private val cipher = KeystoreTokenCipher("bm_device_token_test")

    @Test
    fun roundtrip() {
        assertEquals("secret-token", cipher.decrypt(cipher.encrypt("secret-token")))
    }

    @Test
    fun encryptingTwiceGivesDifferentOutput() {
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"))
    }
}
