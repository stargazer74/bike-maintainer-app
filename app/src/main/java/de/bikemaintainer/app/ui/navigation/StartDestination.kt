package de.bikemaintainer.app.ui.navigation

import android.os.Build
import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.ServerMode
import java.io.IOException

/**
 * The start page follows from the persisted settings alone, so a process restart resumes at the right step. A signed-in
 * user who has not answered the one-time notification question yet (API 33+) starts there.
 */
fun startDestination(settings: StoredSettings, sdkInt: Int = Build.VERSION.SDK_INT): Any {
    val serverUrl = settings.serverUrl ?: return SetupRoute
    val signedIn = when (settings.mode) {
        null -> return SetupRoute
        ServerMode.PORTAL -> settings.encryptedDeviceToken != null
        ServerMode.HOUSEHOLD -> settings.profileId != null
    }
    return when {
        !signedIn && settings.mode == ServerMode.PORTAL -> LoginRoute(serverUrl)
        !signedIn -> ProfilesRoute(serverUrl)
        shouldAskNotifications(settings.notificationsAsked, sdkInt) -> NotificationPermissionRoute
        else -> VehiclesRoute
    }
}

/**
 * [startDestination] of the settings returned by [read]; [SetupRoute] if they cannot be read (I/O error, corrupt file)
 * instead of crashing on start.
 */
suspend fun readStartDestination(sdkInt: Int = Build.VERSION.SDK_INT, read: suspend () -> StoredSettings): Any =
    try {
        startDestination(read(), sdkInt)
    } catch (_: IOException) {
        SetupRoute
    }
