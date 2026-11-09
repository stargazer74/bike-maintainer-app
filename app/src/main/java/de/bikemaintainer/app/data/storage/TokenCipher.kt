package de.bikemaintainer.app.data.storage

import java.security.GeneralSecurityException

/** Implementations throw nothing but [GeneralSecurityException] (wrapping provider runtime errors). */
interface TokenCipher {
    /** @throws GeneralSecurityException if encryption fails (e.g. the Keystore is unavailable). */
    fun encrypt(plain: String): String

    /**
     * @throws UnreadableTokenException if [encrypted] is malformed; AEADBadTagException / InvalidKeyException if it
     * does not match the key (both: unreadable for good); any other [GeneralSecurityException] for a possibly
     * transient failure.
     */
    fun decrypt(encrypted: String): String
}

/** The stored data itself is malformed (bad Base64, too short) and can never be decrypted. */
class UnreadableTokenException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)
