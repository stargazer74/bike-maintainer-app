package de.bikemaintainer.app.ui.navigation

import de.bikemaintainer.app.data.network.SessionStatus
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import de.bikemaintainer.app.data.storage.StoredSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

/** Where to send the user when the session cannot be renewed; `null` while the session is fine or no server is stored. */
fun sessionRedirect(status: SessionStatus, settings: StoredSettings): Any? {
    val server = settings.serverUrl ?: return null
    return when (status) {
        SessionStatus.LOGIN_REQUIRED -> LoginRoute(server, relogin = true)
        SessionStatus.PROFILE_SELECTION_REQUIRED -> ProfilesRoute(server)
        SessionStatus.ACTIVE -> null
    }
}

/**
 * Navigates to login / profile selection whenever [statuses] changes while this is collected. The value present at the
 * start is skipped: the status is sticky, so after an Activity recreation it would replay an old redirect. A status that
 * changed while the app was gone is handled by the start destination (from the stored settings). Does nothing when the
 * target is already the current destination.
 */
suspend fun NavController.redirectOnSessionChanges(statuses: StateFlow<SessionStatus>, settings: Flow<StoredSettings>) {
    statuses.drop(1).collect { status ->
        val target = sessionRedirect(status, settings.first()) ?: return@collect
        val current = currentBackStackEntry?.destination
        val alreadyThere = when (target) {
            is LoginRoute -> current?.hasRoute<LoginRoute>() == true
            is ProfilesRoute -> current?.hasRoute<ProfilesRoute>() == true
            else -> false
        }
        if (!alreadyThere) navigate(target) { popUpTo(0) }
    }
}
