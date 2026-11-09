package de.bikemaintainer.app.data.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore; output is Base64(IV || ciphertext).
 *
 * The Keystore also fails with runtime exceptions (java.security.ProviderException and others) and KeyStore.load with
 * IOException; all of them are wrapped into [GeneralSecurityException], the only exception this class throws.
 */
class KeystoreTokenCipher(private val alias: String = "bm_device_token") : TokenCipher {

    override fun encrypt(plain: String): String = keystore {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(encrypted: String): String = keystore {
        val bytes = try {
            Base64.decode(encrypted, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw UnreadableTokenException("Token is not valid Base64", e)
        }
        if (bytes.size <= IV_SIZE) throw UnreadableTokenException("Token is too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE))
        String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
    }

    // Synchronized: two threads using the cipher for the first time would otherwise both generate a key.
    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private inline fun <T> keystore(block: () -> T): T = try {
        block()
    } catch (e: GeneralSecurityException) {
        throw e
    } catch (e: RuntimeException) { // ProviderException, IllegalStateException, ... from the Keystore
        throw GeneralSecurityException("Keystore failure", e)
    } catch (e: IOException) {
        throw GeneralSecurityException("Keystore failure", e)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
