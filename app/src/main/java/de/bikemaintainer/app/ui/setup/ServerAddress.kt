package de.bikemaintainer.app.ui.setup

import java.net.URI
import java.net.URISyntaxException

/** Normalisation of the server address typed by the user. */
object ServerAddress {
    private val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://(.*)$")

    /**
     * Trims, adds a missing `https://`, lowercases the scheme and ends the address with exactly one `/`.
     * Returns `null` if the result is not a usable http(s) address, including one with credentials (`user:pass@host`):
     * they would be sent along with every request, and `user@host` is a typical phishing disguise.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val match = scheme.matchEntire(trimmed)
        val schemeName = match?.groupValues?.get(1)?.lowercase() ?: "https"
        val rest = (match?.groupValues?.get(2) ?: trimmed).trimEnd('/')
        if (schemeName != "http" && schemeName != "https") return null
        if (rest.isEmpty() || rest.any { it.isWhitespace() }) return null
        val url = "$schemeName://$rest/"
        return try {
            val uri = URI(url)
            if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null) null else url
        } catch (_: URISyntaxException) {
            null
        }
    }

    /** Whether a normalized address uses plain `http://`. */
    fun isCleartext(url: String): Boolean = url.startsWith("http://", ignoreCase = true)
}
