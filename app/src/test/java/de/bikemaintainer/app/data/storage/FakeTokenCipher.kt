package de.bikemaintainer.app.data.storage

import javax.crypto.AEADBadTagException

class FakeTokenCipher : TokenCipher {
    override fun encrypt(plain: String): String = PREFIX + plain

    override fun decrypt(encrypted: String): String {
        if (!encrypted.startsWith(PREFIX)) throw AEADBadTagException("unreadable")
        return encrypted.removePrefix(PREFIX)
    }

    private companion object {
        const val PREFIX = "enc:"
    }
}
