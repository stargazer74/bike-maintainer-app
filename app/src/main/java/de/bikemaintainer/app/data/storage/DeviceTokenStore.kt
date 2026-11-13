package de.bikemaintainer.app.data.storage

import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import javax.crypto.AEADBadTagException

/** Keeps the device token encrypted in the [SettingsStore]. */
class DeviceTokenStore(private val settings: SettingsStore, private val cipher: TokenCipher) {
    private companion object {
        const val MAX_PENDING = 4
    }

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

    /** Makes [token] the device token; pending tokens are dropped (one write). */
    suspend fun set(token: String) {
        settings.setEncryptedDeviceToken(cipher.encrypt(token))
    }

    /**
     * Tokens the app proposed as `nextDeviceToken` in an exchange whose answer never arrived, newest first: one of them
     * may be the server's current token. Unreadable for good → empty (and forgotten); transient failure → empty.
     */
    suspend fun pending(): List<String> {
        val encrypted = settings.current().encryptedPendingDeviceTokens ?: return emptyList()
        return try {
            cipher.decrypt(encrypted).split('\n').filter { it.isNotEmpty() }
        } catch (_: AEADBadTagException) {
            forgetPending()
        } catch (_: InvalidKeyException) {
            forgetPending()
        } catch (_: UnreadableTokenException) {
            forgetPending()
        } catch (_: IllegalArgumentException) {
            forgetPending()
        } catch (_: GeneralSecurityException) {
            emptyList()
        }
    }

    /** Remembers [token] as pending before it is sent; keeps at most [MAX_PENDING] (the oldest go). */
    suspend fun addPending(token: String) = storePending((listOf(token) + pending()).take(MAX_PENDING))

    suspend fun dropPending(token: String) = storePending(pending() - token)

    private suspend fun storePending(list: List<String>) {
        settings.setEncryptedPendingDeviceTokens(if (list.isEmpty()) null else cipher.encrypt(list.joinToString("\n")))
    }

    private suspend fun forgetPending(): List<String> {
        settings.setEncryptedPendingDeviceTokens(null)
        return emptyList()
    }

    /** Stores [serverUrl] (portal mode) together with the encrypted [token] in one write. */
    suspend fun saveLogin(serverUrl: String, token: String) {
        settings.savePortalLogin(serverUrl, cipher.encrypt(token))
    }

    suspend fun clear() {
        settings.setEncryptedDeviceToken(null)
    }
}
