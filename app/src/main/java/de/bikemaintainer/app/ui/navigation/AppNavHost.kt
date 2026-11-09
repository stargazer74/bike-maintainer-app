package de.bikemaintainer.app.ui.navigation

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import de.bikemaintainer.app.ui.notifications.NotificationPermissionScreen
import de.bikemaintainer.app.ui.settings.SettingsScreen
import de.bikemaintainer.app.ui.settings.SettingsViewModel
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
    val scope = rememberCoroutineScope()
    val signedIn: () -> Unit = { scope.launch { navController.navigateAfterSignIn(container.authRepository) } }

    // Session cannot be renewed: send the user to login / profile selection from any screen (see the function).
    LaunchedEffect(Unit) {
        navController.redirectOnSessionChanges(container.sessionState.status, container.authRepository.settings)
    }
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
                onSignedIn = signedIn,
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
                onSignedIn = signedIn,
                onOtherServer = {
                    if (!navController.popBackStack(SetupRoute, inclusive = false)) navController.navigate(SetupRoute)
                },
            )
        }
        composable<VehiclesRoute> {
            VehiclesPlaceholderScreen(onOpenSettings = { navController.navigate(SettingsRoute) })
        }
        composable<NotificationPermissionRoute> {
            NotificationPermissionScreen(
                onDone = {
                    scope.launch {
                        container.authRepository.markNotificationsAsked()
                        navController.navigate(VehiclesRoute) { popUpTo(0) }
                    }
                },
            )
        }
        composable<SettingsRoute> {
            val viewModel: SettingsViewModel = viewModel(
                factory = viewModelFactory { initializer { SettingsViewModel(container.authRepository) } },
            )
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onNavigate = { navController.navigate(it) { popUpTo(0) } },
            )
        }
    }
}
