package de.bikemaintainer.app.ui.navigation

import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import de.bikemaintainer.app.AppContainer
import de.bikemaintainer.app.ui.login.LoginScreen
import de.bikemaintainer.app.ui.login.LoginViewModel
import de.bikemaintainer.app.ui.profiles.ProfilesScreen
import de.bikemaintainer.app.ui.profiles.ProfilesViewModel
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
        composable<LoginRoute> { entry ->
            val route = entry.toRoute<LoginRoute>()
            val viewModel: LoginViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        LoginViewModel(container.authRepository, route.serverUrl, Build.MODEL, route.relogin)
                    }
                },
            )
            LoginScreen(
                viewModel = viewModel,
                serverUrl = route.serverUrl,
                onSignedIn = { navController.navigateAfterSignIn() },
                onOtherServer = {
                    if (!navController.popBackStack(SetupRoute, inclusive = false)) navController.navigate(SetupRoute)
                },
            )
        }
        composable<ProfilesRoute> { entry ->
            val route = entry.toRoute<ProfilesRoute>()
            val viewModel: ProfilesViewModel = viewModel(
                factory = viewModelFactory { initializer { ProfilesViewModel(container.authRepository, route.serverUrl) } },
            )
            ProfilesScreen(
                viewModel = viewModel,
                serverUrl = route.serverUrl,
                onSignedIn = { navController.navigateAfterSignIn() },
                onOtherServer = {
                    if (!navController.popBackStack(SetupRoute, inclusive = false)) navController.navigate(SetupRoute)
                },
            )
        }
        composable<VehiclesRoute> {
            VehiclesPlaceholderScreen(onOpenSettings = { navController.navigate(SettingsRoute) })
        }
        composable<SettingsRoute> { Text("Settings") }
    }
}
