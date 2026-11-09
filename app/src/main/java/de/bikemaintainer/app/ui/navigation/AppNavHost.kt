package de.bikemaintainer.app.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.bikemaintainer.app.AppContainer
import de.bikemaintainer.app.ui.vehicles.VehiclesPlaceholderScreen

@Composable
@Suppress("UNUSED_PARAMETER") // the container is handed to the screens added in the following tasks
fun AppNavHost(container: AppContainer, start: Any) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = start) {
        // Placeholders until the screens of the following tasks are wired in.
        composable<SetupRoute> { Text("Setup") }
        composable<LoginRoute> { Text("Login") }
        composable<ProfilesRoute> { Text("Profiles") }
        composable<VehiclesRoute> {
            VehiclesPlaceholderScreen(onOpenSettings = { navController.navigate(SettingsRoute) })
        }
        composable<SettingsRoute> { Text("Settings") }
    }
}
