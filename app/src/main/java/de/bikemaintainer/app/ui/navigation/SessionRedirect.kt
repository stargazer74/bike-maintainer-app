package de.bikemaintainer.app.ui.navigation

import de.bikemaintainer.app.data.network.SessionStatus
import de.bikemaintainer.app.data.storage.StoredSettings

/** Where to send the user when the session cannot be renewed; `null` while the session is fine or no server is stored. */
fun sessionRedirect(status: SessionStatus, settings: StoredSettings): Any? {
    val server = settings.serverUrl ?: return null
    return when (status) {
        SessionStatus.LOGIN_REQUIRED -> LoginRoute(server, relogin = true)
        SessionStatus.PROFILE_SELECTION_REQUIRED -> ProfilesRoute(server)
        SessionStatus.ACTIVE -> null
    }
}
