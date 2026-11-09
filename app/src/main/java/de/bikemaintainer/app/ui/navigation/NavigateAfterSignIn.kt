package de.bikemaintainer.app.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

/**
 * Where every successful sign-in (device login, profile selection) ends: the vehicles, with the whole first-start flow
 * (setup, login, profiles) removed from the back stack. Further steps (e.g. the notification question) go here.
 */
fun NavController.navigateAfterSignIn() {
    navigate(VehiclesRoute) {
        popUpTo(graph.findStartDestination().id) { inclusive = true }
    }
}
