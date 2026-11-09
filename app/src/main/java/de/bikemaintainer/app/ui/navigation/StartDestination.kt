package de.bikemaintainer.app.ui.navigation

import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.ServerMode

/** The start page follows from the persisted settings alone, so a process restart resumes at the right step. */
fun startDestination(settings: StoredSettings): Any {
    val serverUrl = settings.serverUrl ?: return SetupRoute
    return when (settings.mode) {
        null -> SetupRoute
        ServerMode.PORTAL -> if (settings.encryptedDeviceToken == null) LoginRoute(serverUrl) else VehiclesRoute
        ServerMode.HOUSEHOLD -> if (settings.profileId == null) ProfilesRoute(serverUrl) else VehiclesRoute
    }
}
