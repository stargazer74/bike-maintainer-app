package de.bikemaintainer.app.ui.navigation

import android.os.Build
import androidx.navigation.NavController
import de.bikemaintainer.app.data.repository.AuthRepository
import kotlinx.coroutines.flow.first

/** Notification permission exists as runtime permission from API 33. */
internal const val NOTIFICATION_PERMISSION_API = 33

/** Whether the one-time notification question comes next. */
fun shouldAskNotifications(notificationsAsked: Boolean, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    !notificationsAsked && sdkInt >= NOTIFICATION_PERMISSION_API

/**
 * Where every successful sign-in (device login, profile selection) ends: the one-time notification question (API 33+)
 * or the vehicles, with the whole first-start flow (setup, login, profiles) removed from the back stack.
 */
suspend fun NavController.navigateAfterSignIn(auth: AuthRepository) {
    val asked = auth.settings.first().notificationsAsked
    val target: Any = if (shouldAskNotifications(asked)) NotificationPermissionRoute else VehiclesRoute
    navigate(target) {
        // Clears everything: the start destination may be gone already (after logout or a session redirect).
        popUpTo(0)
    }
}
