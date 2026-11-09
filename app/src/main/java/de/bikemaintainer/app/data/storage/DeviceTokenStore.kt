package de.bikemaintainer.app.data.storage

import java.security.GeneralSecurityException

/** Keeps the device token encrypted in the [SettingsStore]. */
class DeviceTokenStore(private val settings: SettingsStore, private val cipher: TokenCipher) {

    /** The token, or null if none is stored or it cannot be decrypted (the broken entry is then removed). */
    suspend fun get(): String? {
        val encrypted = settings.current().encryptedDeviceToken ?: return null
        return try {
            cipher.decrypt(encrypted)
        } catch (_: GeneralSecurityException) {
            settings.setEncryptedDeviceToken(null)
            null
        } catch (_: IllegalArgumentException) {
            settings.setEncryptedDeviceToken(null)
            null
        }
    }

    suspend fun set(token: String) {
        settings.setEncryptedDeviceToken(cipher.encrypt(token))
    }

    suspend fun clear() {
        settings.setEncryptedDeviceToken(null)
    }
}
