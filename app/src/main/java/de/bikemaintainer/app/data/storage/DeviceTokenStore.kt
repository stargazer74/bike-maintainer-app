package de.bikemaintainer.app.data.storage

import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import javax.crypto.AEADBadTagException

/** Keeps the device token encrypted in the [SettingsStore]. */
class DeviceTokenStore(private val settings: SettingsStore, private val cipher: TokenCipher) {

    /**
     * The token, or null if none is stored or it cannot be decrypted. An entry that is unreadable for good (wrong key,
     * tampered or malformed data) is removed; on any other (possibly transient Keystore) failure it is kept, so that
     * [SettingsStore.settings] still shows a stored token.
     */
    suspend fun get(): String? {
        val encrypted = settings.current().encryptedDeviceToken ?: return null
        return try {
            cipher.decrypt(encrypted)
        } catch (_: AEADBadTagException) {
            forget()
        } catch (_: InvalidKeyException) {
            forget()
        } catch (_: UnreadableTokenException) {
            forget()
        } catch (_: IllegalArgumentException) {
            forget()
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private suspend fun forget(): String? {
        settings.setEncryptedDeviceToken(null)
        return null
    }

    suspend fun set(token: String) {
        settings.setEncryptedDeviceToken(cipher.encrypt(token))
    }

    /** Stores [serverUrl] (portal mode) together with the encrypted [token] in one write. */
    suspend fun saveLogin(serverUrl: String, token: String) {
        settings.savePortalLogin(serverUrl, cipher.encrypt(token))
    }

    suspend fun clear() {
        settings.setEncryptedDeviceToken(null)
    }
}
