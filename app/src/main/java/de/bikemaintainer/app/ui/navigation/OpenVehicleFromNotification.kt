package de.bikemaintainer.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Shows the vehicle of a tapped notification above the list. Waits until the navigation graph has a destination; on
 * login, setup or profile selection (e.g. after logout) the request is discarded, so nobody lands on a vehicle
 * without a session.
 */
@Composable
internal fun OpenVehicleFromNotification(
    navController: NavController,
    requests: Flow<Long?>,
    onHandled: () -> Unit,
) {
    LaunchedEffect(navController) {
        requests.filterNotNull().collect { id ->
            // The first entry only exists once the graph is set; at app start this is the stored start destination.
            val current = navController.currentBackStackEntryFlow.first().destination
            if (current.isAuthenticated()) {
                navController.navigate(VehicleRoute(id)) { popUpTo<VehiclesRoute>() }
            }
            onHandled()
        }
    }
}
