package de.bikemaintainer.app.ui.navigation

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import de.bikemaintainer.app.data.network.SessionStatus
import de.bikemaintainer.app.data.repository.AuthRepository
import de.bikemaintainer.app.data.repository.MaintenanceRepository
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.ui.login.LoginScreen
import de.bikemaintainer.app.ui.login.LoginViewModel
import de.bikemaintainer.app.ui.notifications.NotificationPermissionScreen
import de.bikemaintainer.app.ui.profiles.ProfilesScreen
import de.bikemaintainer.app.ui.profiles.ProfilesViewModel
import de.bikemaintainer.app.ui.settings.SettingsScreen
import de.bikemaintainer.app.ui.settings.SettingsViewModel
import de.bikemaintainer.app.ui.setup.SetupScreen
import de.bikemaintainer.app.ui.setup.SetupViewModel
import de.bikemaintainer.app.ui.vehicle.VehicleScreen
import de.bikemaintainer.app.ui.vehicle.VehicleViewModel
import de.bikemaintainer.app.ui.vehicles.VehiclesScreen
import de.bikemaintainer.app.ui.vehicles.VehiclesViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Everything the navigation graph needs, so tests can pass fakes instead of the real container. */
@Stable
class AppDependencies(
    val auth: AuthRepository,
    val vehicles: VehicleRepository,
    val maintenance: MaintenanceRepository,
    val sessionStatus: StateFlow<SessionStatus>,
)

@Composable
fun AppNavHost(deps: AppDependencies, start: Any, navController: NavHostController = rememberNavController()) {
    val scope = rememberCoroutineScope()
    val signedIn: () -> Unit = { scope.launch { navController.navigateAfterSignIn(deps.auth) } }

    // Session cannot be renewed: send the user to login / profile selection from any screen (see the function).
    LaunchedEffect(Unit) {
        navController.redirectOnSessionChanges(deps.sessionStatus, deps.auth.settings)
    }
    NavHost(navController = navController, startDestination = start) {
        composable<SetupRoute> {
            val viewModel: SetupViewModel = viewModel(
                factory = viewModelFactory { initializer { SetupViewModel(deps.auth) } },
            )
            SetupScreen(viewModel = viewModel, onNavigate = { navController.navigate(it) })
        }
        composable<LoginRoute> { entry ->
            val route = entry.toRoute<LoginRoute>()
            val viewModel: LoginViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        LoginViewModel(deps.auth, route.serverUrl, Build.MODEL, route.relogin)
                    }
                },
            )
            LoginScreen(
                viewModel = viewModel,
                serverUrl = route.serverUrl,
                onSignedIn = signedIn,
                onOtherServer = {
                    if (!navController.popBackStack(SetupRoute, inclusive = false)) navController.navigate(SetupRoute)
                },
            )
        }
        composable<ProfilesRoute> { entry ->
            val route = entry.toRoute<ProfilesRoute>()
            val viewModel: ProfilesViewModel = viewModel(
                factory = viewModelFactory { initializer { ProfilesViewModel(deps.auth, route.serverUrl) } },
            )
            ProfilesScreen(
                viewModel = viewModel,
                serverUrl = route.serverUrl,
                onSignedIn = signedIn,
                onOtherServer = {
                    if (!navController.popBackStack(SetupRoute, inclusive = false)) navController.navigate(SetupRoute)
                },
            )
        }
        composable<VehiclesRoute> {
            val viewModel: VehiclesViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { VehiclesViewModel(deps.vehicles, deps.auth.settings) }
                },
            )
            VehiclesScreen(
                viewModel = viewModel,
                onOpenVehicle = { navController.navigate(VehicleRoute(it)) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
        }
        composable<VehicleRoute> { entry ->
            val route = entry.toRoute<VehicleRoute>()
            val back = navController.popBackOnce()
            val viewModel: VehicleViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        VehicleViewModel(route.vehicleId, deps.vehicles, deps.maintenance)
                    }
                },
            )
            VehicleScreen(viewModel = viewModel, onBack = back)
        }
        composable<NotificationPermissionRoute> {
            NotificationPermissionScreen(
                onDone = {
                    scope.launch {
                        deps.auth.markNotificationsAsked()
                        navController.navigate(VehiclesRoute) { popUpTo(0) }
                    }
                },
            )
        }
        composable<SettingsRoute> {
            val back = navController.popBackOnce()
            val viewModel: SettingsViewModel = viewModel(
                factory = viewModelFactory { initializer { SettingsViewModel(deps.auth) } },
            )
            SettingsScreen(
                viewModel = viewModel,
                onBack = back,
                onNavigate = { navController.navigate(it) { popUpTo(0) } },
            )
        }
    }
}
