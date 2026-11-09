package de.bikemaintainer.app.data.storage

interface TokenCipher {
    fun encrypt(plain: String): String

    /** @throws java.security.GeneralSecurityException if [encrypted] cannot be read. */
    fun decrypt(encrypted: String): String
}
