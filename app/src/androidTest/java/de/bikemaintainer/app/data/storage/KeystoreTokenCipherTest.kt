package de.bikemaintainer.app.data.storage

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun malformedDataIsUnreadableForGood() {
        assertThrows(UnreadableTokenException::class.java) { cipher.decrypt("not base64 !") }
        assertThrows(UnreadableTokenException::class.java) { cipher.decrypt("AAAA") }
    }

    @Test
    fun tamperedDataFailsTheTagCheck() {
        val bytes = Base64.decode(cipher.encrypt("secret-token"), Base64.NO_WRAP)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(Base64.encodeToString(bytes, Base64.NO_WRAP)) }
    }
}
