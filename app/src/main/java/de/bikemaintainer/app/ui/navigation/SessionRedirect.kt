package de.bikemaintainer.app.ui.navigation

import de.bikemaintainer.app.data.network.SessionStatus
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import de.bikemaintainer.app.data.storage.StoredSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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
 * Navigates to login / profile selection whenever [statuses] requires it while an authenticated page (vehicles,
 * settings, notification question) is shown, including the value present when collection starts: after an Activity
 * recreation the restored back stack may still show such a page although the session broke meanwhile. On setup, login
 * and profile selection nothing happens, so a replayed status does not interrupt a sign-in in progress. Does nothing
 * when the target is already the current destination.
 */
suspend fun NavController.redirectOnSessionChanges(statuses: StateFlow<SessionStatus>, settings: Flow<StoredSettings>) {
    statuses.collect { status ->
        val current = currentBackStackEntry?.destination ?: return@collect
        if (!current.isAuthenticated()) return@collect
        val target = sessionRedirect(status, settings.first()) ?: return@collect
        navigate(target) { popUpTo(0) }
    }
}

/** Pages that need a session. Login and profile selection are never among them, so a target is never shown twice. */
private fun NavDestination.isAuthenticated(): Boolean =
    hasRoute<VehiclesRoute>() || hasRoute<VehicleRoute>() || hasRoute<LogMaintenanceRoute>() || hasRoute<SettingsRoute>() || hasRoute<NotificationPermissionRoute>()
