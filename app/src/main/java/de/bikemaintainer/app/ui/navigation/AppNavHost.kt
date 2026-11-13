package de.bikemaintainer.app.ui.navigation

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
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
import de.bikemaintainer.app.data.repository.MeasurementRepository
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.notifications.DueNotifications
import de.bikemaintainer.app.notifications.ReminderScheduler
import de.bikemaintainer.app.notifications.syncQuietly
import de.bikemaintainer.app.ui.login.LoginScreen
import de.bikemaintainer.app.ui.login.LoginViewModel
import de.bikemaintainer.app.ui.logmaintenance.LogMaintenanceScreen
import de.bikemaintainer.app.ui.logmaintenance.LogMaintenanceViewModel
import de.bikemaintainer.app.ui.logmaintenance.ScanTransfer
import de.bikemaintainer.app.ui.notifications.NotificationPermissionScreen
import de.bikemaintainer.app.ui.profiles.ProfilesScreen
import de.bikemaintainer.app.ui.profiles.ProfilesViewModel
import de.bikemaintainer.app.ui.register.RegisterScreen
import de.bikemaintainer.app.ui.register.RegisterViewModel
import de.bikemaintainer.app.ui.scan.ScanScreen
import de.bikemaintainer.app.ui.scan.ScanViewModel
import de.bikemaintainer.app.ui.scan.WorksheetScanner
import de.bikemaintainer.app.ui.settings.SettingsScreen
import de.bikemaintainer.app.ui.settings.SettingsViewModel
import de.bikemaintainer.app.ui.setup.PORTAL_SERVER
import de.bikemaintainer.app.ui.setup.ServerScanScreen
import de.bikemaintainer.app.ui.setup.SetupScreen
import de.bikemaintainer.app.ui.setup.SetupViewModel
import de.bikemaintainer.app.ui.setup.WelcomeScreen
import de.bikemaintainer.app.ui.taskform.TaskFormScreen
import de.bikemaintainer.app.ui.taskform.TaskFormViewModel
import de.bikemaintainer.app.ui.vehicle.VehicleScreen
import de.bikemaintainer.app.ui.vehicle.VehicleViewModel
import de.bikemaintainer.app.ui.vehicleform.VehicleFormScreen
import de.bikemaintainer.app.ui.vehicleform.VehicleFormViewModel
import de.bikemaintainer.app.ui.vehicles.VehiclesScreen
import de.bikemaintainer.app.ui.vehicles.VehiclesViewModel
import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.scan.ScanOutcome
import kotlinx.serialization.json.Json
import de.bikemaintainer.app.data.repository.PendingRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Everything the navigation graph needs, so tests can pass fakes instead of the real container. */
@Stable
class AppDependencies(
    val auth: AuthRepository,
    val vehicles: VehicleRepository,
    val maintenance: MaintenanceRepository,
    val measurements: MeasurementRepository,
    val sessionStatus: StateFlow<SessionStatus>,
    val reminders: ReminderScheduler,
    val notifications: DueNotifications,
    val notificationsAllowed: () -> Boolean,
    /** Vehicle a tapped notification wants to open; reset via [onVehicleOpened] once handled or discarded. */
    val openVehicle: StateFlow<Long?>,
    val onVehicleOpened: () -> Unit,
    /** Reads photographed worksheets; tests without a camera keep the default. */
    val worksheetScanner: WorksheetScanner = WorksheetScanner { ScanOutcome.NoSheet },
    /** Recordings not sent yet (app#65). */
    val pending: PendingRepository = PendingRepository(),
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
            WelcomeScreen(
                onOnlineAccount = dropUnlessResumed { navController.navigate(LoginRoute(PORTAL_SERVER)) },
                onOwnServer = dropUnlessResumed { navController.navigate(OwnServerRoute) },
            )
        }
        composable<OwnServerRoute> { entry ->
            val viewModel: SetupViewModel = viewModel(
                factory = viewModelFactory { initializer { SetupViewModel(deps.auth) } },
            )
            val scanned by entry.savedStateHandle.getStateFlow<String?>(SERVER_QR_KEY, null).collectAsState()
            LaunchedEffect(scanned) {
                scanned?.let {
                    entry.savedStateHandle[SERVER_QR_KEY] = null
                    viewModel.onScanned(it)
                }
            }
            SetupScreen(
                viewModel = viewModel,
                onNavigate = { navController.navigate(it) },
                onScan = dropUnlessResumed { navController.navigate(ScanServerRoute) },
            )
        }
        composable<ScanServerRoute> { entry ->
            val back = navController.popBackOnce()
            ServerScanScreen(
                onBack = back,
                onAddress = { address ->
                    // Only the entry still on top hands the address over, exactly once.
                    if (navController.currentBackStackEntry == entry) {
                        navController.previousBackStackEntry?.savedStateHandle?.set(SERVER_QR_KEY, address)
                        navController.popBackStack()
                    }
                },
            )
        }
        composable<RegisterRoute> { entry ->
            val route = entry.toRoute<RegisterRoute>()
            val viewModel: RegisterViewModel = viewModel(
                factory = viewModelFactory { initializer { RegisterViewModel(deps.auth, route.serverUrl) } },
            )
            RegisterScreen(
                viewModel = viewModel,
                onToLogin = {
                    if (!navController.popBackStack(LoginRoute(route.serverUrl), inclusive = false)) {
                        navController.navigate(LoginRoute(route.serverUrl)) { popUpTo(entry.destination.id) { inclusive = true } }
                    }
                },
            )
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
                onCreateAccount = dropUnlessResumed { navController.navigate(RegisterRoute(route.serverUrl)) },
            )
        }
        composable<ProfilesRoute> { entry ->
            val route = entry.toRoute<ProfilesRoute>()
            val viewModel: ProfilesViewModel = viewModel(
                factory = viewModelFactory { initializer { ProfilesViewModel(deps.auth, route.serverUrl) } },
            )
            ProfilesScreen(
                viewModel = viewModel,
                onSignedIn = signedIn,
                onOtherServer = {
                    if (!navController.popBackStack(SetupRoute, inclusive = false)) navController.navigate(SetupRoute)
                },
            )
        }
        composable<VehiclesRoute> {
            val viewModel: VehiclesViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { VehiclesViewModel(deps.vehicles, deps.auth, deps.pending) }
                },
            )
            VehiclesScreen(
                viewModel = viewModel,
                onOpenVehicle = { navController.navigate(VehicleRoute(it)) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onAddVehicle = dropUnlessResumed { navController.navigate(VehicleFormRoute()) },
            )
        }
        composable<VehicleRoute> { entry ->
            val route = entry.toRoute<VehicleRoute>()
            val back = navController.popBackOnce()
            val viewModel: VehicleViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        VehicleViewModel(
                            route.vehicleId,
                            deps.vehicles,
                            deps.maintenance,
                            deps.measurements,
                            deps.auth,
                            cancelNotification = deps.notifications::cancelVehicle,
                            pending = deps.pending,
                        )
                    }
                },
            )
            VehicleScreen(
                viewModel = viewModel,
                onBack = back,
                onLogMaintenance = dropUnlessResumed { navController.navigate(LogMaintenanceRoute(route.vehicleId)) },
                logResult = entry.savedStateHandle.getStateFlow<String?>(LOG_RESULT_KEY, null),
                onLogResultShown = { entry.savedStateHandle[LOG_RESULT_KEY] = null },
                onEditVehicle = dropUnlessResumed { navController.navigate(VehicleFormRoute(route.vehicleId)) },
                onAddTask = dropUnlessResumed { navController.navigate(TaskFormRoute(route.vehicleId)) },
                // Like dropUnlessResumed, with the task: a double tap must not open the form twice.
                onEditTask = { taskId ->
                    if (entry.lifecycle.currentState == Lifecycle.State.RESUMED) {
                        navController.navigate(TaskFormRoute(route.vehicleId, taskId))
                    }
                },
                // The list reloads when it is shown again.
                onDeleted = back,
            )
        }
        composable<VehicleFormRoute> { entry ->
            val route = entry.toRoute<VehicleFormRoute>()
            val back = navController.popBackOnce()
            val viewModel: VehicleFormViewModel = viewModel(
                factory = viewModelFactory { initializer { VehicleFormViewModel(route.vehicleId, deps.vehicles, deps.auth) } },
            )
            VehicleFormScreen(
                viewModel = viewModel,
                onBack = back,
                onSaved = { id ->
                    // Only the entry still on top leaves, exactly once: an edit returns to the detail (it reloads on
                    // resume), a new vehicle opens its detail instead of the form.
                    if (navController.currentBackStackEntry == entry) {
                        if (route.vehicleId == null) {
                            navController.navigate(VehicleRoute(id)) { popUpTo<VehicleFormRoute> { inclusive = true } }
                        } else {
                            navController.popBackStack()
                        }
                    }
                },
            )
        }
        composable<TaskFormRoute> { entry ->
            val route = entry.toRoute<TaskFormRoute>()
            val back = navController.popBackOnce()
            val viewModel: TaskFormViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { TaskFormViewModel(route.vehicleId, route.taskId, deps.maintenance, deps.auth) }
                },
            )
            TaskFormScreen(
                viewModel = viewModel,
                onBack = back,
                onSaved = { if (navController.currentBackStackEntry == entry) navController.popBackStack() },
            )
        }
        composable<LogMaintenanceRoute> { entry ->
            val route = entry.toRoute<LogMaintenanceRoute>()
            val back = navController.popBackOnce()
            val viewModel: LogMaintenanceViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        LogMaintenanceViewModel(
                            route.vehicleId,
                            deps.vehicles,
                            deps.maintenance,
                            createSavedStateHandle(),
                            deps.measurements,
                            accessSource = deps.auth,
                        )
                    }
                },
            )
            val scanResult by entry.savedStateHandle.getStateFlow<String?>(SCAN_RESULT_KEY, null).collectAsState()
            LaunchedEffect(scanResult) {
                scanResult?.let { json ->
                    runCatching { Json.decodeFromString<ScanTransfer>(json) }.getOrNull()?.let(viewModel::applyScan)
                    entry.savedStateHandle[SCAN_RESULT_KEY] = null
                }
            }
            LogMaintenanceScreen(
                viewModel = viewModel,
                onBack = back,
                onScanWorksheet = dropUnlessResumed {
                    val templates = viewModel.state.value.measurementContext?.templates.orEmpty()
                    navController.navigate(ScanWorksheetRoute(route.vehicleId, templates.map { it.name }))
                },
                onDone = { result ->
                    // Idempotent: only the entry that is still on top may hand over its result and pop.
                    if (navController.currentBackStackEntry == entry) {
                        navController.previousBackStackEntry?.savedStateHandle?.set(LOG_RESULT_KEY, result.name)
                        navController.popBackStack()
                    }
                },
            )
        }
        composable<ScanWorksheetRoute> { entry ->
            val route = entry.toRoute<ScanWorksheetRoute>()
            val back = navController.popBackOnce()
            val viewModel: ScanViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ScanViewModel(
                            route.vehicleId,
                            MeasurementTemplate.entries.filter { it.name in route.templates }.toSet(),
                            deps.worksheetScanner,
                        )
                    }
                },
            )
            ScanScreen(
                viewModel = viewModel,
                onBack = back,
                onAccepted = { transfer ->
                    // Only the entry still on top hands its values over, exactly once.
                    if (navController.currentBackStackEntry == entry) {
                        navController.previousBackStackEntry?.savedStateHandle?.set(SCAN_RESULT_KEY, Json.encodeToString(transfer))
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
                factory = viewModelFactory { initializer {
                    SettingsViewModel(deps.auth, deps.reminders, deps.notifications, deps.pending, deps.notificationsAllowed)
                } },
            )
            SettingsScreen(
                viewModel = viewModel,
                onBack = back,
                onNavigate = { navController.navigate(it) { popUpTo(0) } },
            )
        }
    }
}
