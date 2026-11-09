package de.bikemaintainer.app.ui.common

/**
 * The app offers sign-in only: no registration and no link to it (a later subscription is taken out on the web only).
 *
 * Links into the web app (relative to the server address) and the portal website (per app language).
 */
object WebLinks {
    fun forgotPassword(serverUrl: String): String = "${serverUrl.trimEnd('/')}/passwort-vergessen"

    fun imprint(websiteUrl: String, german: Boolean): String =
        website(websiteUrl, if (german) "/impressum/" else "/en/legal-notice/")

    fun privacy(websiteUrl: String, german: Boolean): String =
        website(websiteUrl, if (german) "/datenschutz/" else "/en/privacy/")

    fun home(websiteUrl: String, german: Boolean): String = website(websiteUrl, if (german) "/" else "/en/")

    private fun website(websiteUrl: String, path: String) = websiteUrl.trimEnd('/') + path
}
