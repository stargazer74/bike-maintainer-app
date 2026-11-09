package de.bikemaintainer.app.ui.navigation

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.createSavedStateHandle
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
import de.bikemaintainer.app.notifications.DueNotifications
import de.bikemaintainer.app.notifications.ReminderScheduler
import de.bikemaintainer.app.notifications.syncQuietly
import de.bikemaintainer.app.ui.login.LoginScreen
import de.bikemaintainer.app.ui.login.LoginViewModel
import de.bikemaintainer.app.ui.logmaintenance.LogMaintenanceScreen
import de.bikemaintainer.app.ui.logmaintenance.LogMaintenanceViewModel
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
    val reminders: ReminderScheduler,
    val notifications: DueNotifications,
    val notificationsAllowed: () -> Boolean,
    /** Vehicle a tapped notification wants to open; reset via [onVehicleOpened] once handled or discarded. */
    val openVehicle: StateFlow<Long?>,
    val onVehicleOpened: () -> Unit,
)

@Composable
fun AppNavHost(deps: AppDependencies, start: Any, navController: NavHostController = rememberNavController()) {
    val scope = rememberCoroutineScope()
    val signedIn: () -> Unit = {
        // Drops the "sign in again" hint and vehicle notifications of the previous account/session.
        deps.notifications.cancelAll()
        scope.launch {
            navController.navigateAfterSignIn(deps.auth)
            // A new login (or profile) starts the daily check.
            deps.reminders.syncQuietly()
        }
    }
    OpenVehicleFromNotification(navController, deps.openVehicle, deps.onVehicleOpened)

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
            VehicleScreen(
                viewModel = viewModel,
                onBack = back,
                onLogMaintenance = dropUnlessResumed { navController.navigate(LogMaintenanceRoute(route.vehicleId)) },
                logResult = entry.savedStateHandle.getStateFlow<String?>(LOG_RESULT_KEY, null),
                onLogResultShown = { entry.savedStateHandle[LOG_RESULT_KEY] = null },
            )
        }
        composable<LogMaintenanceRoute> { entry ->
            val route = entry.toRoute<LogMaintenanceRoute>()
            val back = navController.popBackOnce()
            val viewModel: LogMaintenanceViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        LogMaintenanceViewModel(route.vehicleId, deps.vehicles, deps.maintenance, createSavedStateHandle())
                    }
                },
            )
            LogMaintenanceScreen(
                viewModel = viewModel,
                onBack = back,
                onDone = { result ->
                    // Idempotent: only the entry that is still on top may hand over its result and pop.
                    if (navController.currentBackStackEntry == entry) {
                        navController.previousBackStackEntry?.savedStateHandle?.set(LOG_RESULT_KEY, result.name)
                        navController.popBackStack()
                    }
                },
            )
        }
        composable<NotificationPermissionRoute> {
            NotificationPermissionScreen(
                onDone = {
                    scope.launch {
                        deps.auth.markNotificationsAsked()
                        navController.navigate(VehiclesRoute) { popUpTo(0) }
                        // The permission may have been granted just now.
                        deps.reminders.syncQuietly()
                    }
                },
            )
        }
        composable<SettingsRoute> {
            val back = navController.popBackOnce()
            val viewModel: SettingsViewModel = viewModel(
                factory = viewModelFactory { initializer { SettingsViewModel(deps.auth, deps.reminders, deps.notifications, deps.notificationsAllowed) } },
            )
            SettingsScreen(
                viewModel = viewModel,
                onBack = back,
                onNavigate = { navController.navigate(it) { popUpTo(0) } },
            )
        }
    }
}
