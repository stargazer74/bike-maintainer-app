package de.bikemaintainer.app.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.bikemaintainer.app.AppContainer
import de.bikemaintainer.app.ui.setup.SetupScreen
import de.bikemaintainer.app.ui.setup.SetupViewModel
import de.bikemaintainer.app.ui.vehicles.VehiclesPlaceholderScreen

@Composable
fun AppNavHost(container: AppContainer, start: Any) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = start) {
        // Placeholders until the screens of the following tasks are wired in.
        composable<SetupRoute> {
            val viewModel: SetupViewModel = viewModel(
                factory = viewModelFactory { initializer { SetupViewModel(container.authRepository) } },
            )
            SetupScreen(viewModel = viewModel, onNavigate = { navController.navigate(it) })
        }
        composable<LoginRoute> { Text("Login") }
        composable<ProfilesRoute> { Text("Profiles") }
        composable<VehiclesRoute> {
            VehiclesPlaceholderScreen(onOpenSettings = { navController.navigate(SettingsRoute) })
        }
        composable<SettingsRoute> { Text("Settings") }
    }
}
