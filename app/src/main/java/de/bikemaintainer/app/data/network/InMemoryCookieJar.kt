package de.bikemaintainer.app.data.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Session and CSRF cookies live in memory only: nothing sensitive is written to disk, and a process restart simply
 * means a new session (the device token is the persistent credential).
 */
class InMemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (incoming in cookies) {
            // A cookie is identified by (name, domain, path); a newer one replaces the old one.
            this.cookies.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
            if (incoming.expiresAt > System.currentTimeMillis()) this.cookies += incoming
        }
        this.cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() {
        cookies.clear()
    }

    fun valueOf(url: HttpUrl, name: String): String? = loadForRequest(url).firstOrNull { it.name == name }?.value
}
