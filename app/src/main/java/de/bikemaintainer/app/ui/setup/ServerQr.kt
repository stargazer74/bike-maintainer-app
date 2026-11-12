package de.bikemaintainer.app.ui.setup

/** What a QR code for connecting the app contains: the address of the server as an ordinary http(s) URL. */
object ServerQr {
    private val httpUrl = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    /**
     * The normalised server address from the text of a QR code, or null if it is no http(s) address. Query and fragment
     * are dropped – a server address has neither. The address then goes through the same checks as a typed one.
     */
    fun address(text: String): String? {
        val trimmed = text.trim()
        if (!httpUrl.matches(trimmed)) return null
        return ServerAddress.normalize(trimmed.substringBefore('#').substringBefore('?'))
    }
}
