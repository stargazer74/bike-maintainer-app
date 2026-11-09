package de.bikemaintainer.app.data.storage

import java.security.GeneralSecurityException

class FakeTokenCipher : TokenCipher {
    override fun encrypt(plain: String): String = PREFIX + plain

    override fun decrypt(encrypted: String): String {
        if (!encrypted.startsWith(PREFIX)) throw GeneralSecurityException("unreadable")
        return encrypted.removePrefix(PREFIX)
    }

    private companion object {
        const val PREFIX = "enc:"
    }
}
