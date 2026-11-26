package de.bikemaintainer.app.ui.navigation

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import de.bikemaintainer.app.data.network.SessionState
import de.bikemaintainer.app.data.network.SessionStatus
import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleSummary
import de.bikemaintainer.app.domain.VehicleType
import de.bikemaintainer.app.notifications.FakeDueNotifications
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.FakeMaintenanceRepository
import de.bikemaintainer.app.ui.FakeMeasurementRepository
import de.bikemaintainer.app.ui.FakeReminderScheduler
import de.bikemaintainer.app.ui.FakeVehicleRepository
import de.bikemaintainer.app.ui.MainDispatcherRule
import de.bikemaintainer.app.ui.odometer.FakeOdometerScanner
import de.bikemaintainer.app.ui.odometer.OdometerViewModel
import de.bikemaintainer.app.domain.scan.GrayImage
import androidx.lifecycle.ViewModelProvider
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real [AppNavHost] with fake repositories and a [TestNavHostController]. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de")
class AppNavGraphTest {
    @get:Rule(order = 0) val main = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val auth = FakeAuthRepository().also {
        it.settings.value = StoredSettings("https://h", ServerMode.PORTAL, "tok", null, notificationsAsked = true)
    }
    private val vehicle = Vehicle(7, "Africa Twin", VehicleType.MOTORCYCLE, null, null, 2020, LocalDate.of(2020, 5, 1), 12345)
    private val vehicles = FakeVehicleRepository().also {
        it.summariesResult = Outcome.Success(listOf(VehicleSummary(vehicle, TaskStatus.OVERDUE, 2)))
        it.vehicleResult = Outcome.Success(vehicle)
    }
    private val maintenance = FakeMaintenanceRepository()
    private val session = SessionState()
    private val reminders = FakeReminderScheduler()
    private val notifications = FakeDueNotifications()
    private val openVehicle = MutableStateFlow<Long?>(null)
    private var vehicleOpened = 0
    private lateinit var nav: TestNavHostController
    private val odometer = FakeOdometerScanner()

    private fun show(start: Any) {
        compose.setContent {
            nav = TestNavHostController(LocalContext.current).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
            AppNavHost(AppDependencies(
                    auth, vehicles, maintenance, FakeMeasurementRepository(), session.status,
                    reminders, notifications, { true }, openVehicle, { vehicleOpened++; openVehicle.value = null },
                    odometerScanner = odometer,
                ), start, nav)
        }
        compose.waitForIdle()
    }

    private fun stack() = nav.currentBackStack.value.mapNotNull { it.destination.route?.substringBefore('?')?.substringBefore('/')?.substringAfterLast('.') }
        .filter { !it.startsWith("androidx") }

    private fun current() = nav.currentDestination?.route.orEmpty()

    @Test fun openingAVehicleAndGoingBackReturnsToTheList() {
        show(VehiclesRoute)
        compose.onNodeWithText("Africa Twin").performClick()
        compose.waitForIdle()
        assertEquals(true, current().contains("VehicleRoute"))
        assertEquals(listOf("VehiclesRoute", "VehicleRoute"), stack())
        assertEquals(1, vehicles.summariesCalls)
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.waitForIdle()
        assertEquals(listOf("VehiclesRoute"), stack())
        // Returning to the list reloads it (the resume gate lives in the ViewModel, not in the dropped composition).
        assertEquals(2, vehicles.summariesCalls)
    }

    @Test fun vehicleDeletedWhileDetailIsOpenOffersBack() {
        show(VehiclesRoute)
        compose.onNodeWithText("Africa Twin").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("12.345 km").assertExists()
        vehicles.vehicleResult = Outcome.Failure(AppError.NotFound)
        compose.onNodeWithText("12.345 km").performTouchInput { swipeDown(startY = top, endY = top + 1500f, durationMillis = 500) }
        compose.waitForIdle()
        compose.onNodeWithText("Das Fahrzeug wurde nicht gefunden.").assertExists()
        compose.onNodeWithText("12.345 km").assertDoesNotExist()
        compose.onNodeWithText("Zurück").performClick()
        compose.waitForIdle()
        assertEquals(listOf("VehiclesRoute"), stack())
    }

    /** app#90: a server answering at once must not keep the app on "not found" after the delete. */
    @Test fun deletingAVehicleReturnsToTheListAlsoWithAnImmediateAnswer() {
        show(VehiclesRoute)
        compose.onNodeWithText("Africa Twin").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Fahrzeug bearbeiten").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Fahrzeug löschen").performScrollTo().performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Löschen").performClick()
        // The detail is still entering when the delete returns: no waiting for the transition before the answer.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(listOf(7L), vehicles.deleteVehicleCalls)
        assertEquals(listOf("VehiclesRoute"), stack())
    }

    /** app#100: the delete answering while a form is open above the detail must not take the form (and its input) along. */
    @Test @Config(qualifiers = "de-h1200dp") fun deleteAnsweringWhileAFormIsOpenKeepsTheForm() {
        maintenance.tasksResult = Outcome.Success(listOf(task(1, "Ölwechsel", TaskStatus.OVERDUE)))
        val gate = CompletableDeferred<Unit>()
        vehicles.deleteVehicleGate = gate
        show(VehiclesRoute)
        compose.onNodeWithText("Africa Twin").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Fahrzeug bearbeiten").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Fahrzeug löschen").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Löschen").performClick()
        compose.waitForIdle()
        assertEquals(listOf(7L), vehicles.deleteVehicleCalls)
        // The detail's buttons are locked while deleting; a form on top is opened directly here. The answer arrives
        // while the form is still entering, when the detail is still shown and reacts to it.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { nav.navigate(LogMaintenanceRoute(7)) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread { gate.complete(Unit) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(listOf("VehiclesRoute", "VehicleRoute", "LogMaintenanceRoute"), stack())
        // Back from the form, the detail of the deleted vehicle leaves right away.
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.waitForIdle()
        assertEquals(listOf("VehiclesRoute"), stack())
    }

    @Test fun loginRequiredOnVehicleDetailRedirects() {
        show(VehicleRoute(7))
        session.require(SessionStatus.LOGIN_REQUIRED)
        compose.waitForIdle()
        assertEquals(listOf("LoginRoute"), stack())
        assertEquals(LoginRoute("https://h", relogin = true), nav.currentBackStackEntry?.toRoute<LoginRoute>())
    }

    @Test fun settingsLogoutClearsStackToLogin() {
        show(VehiclesRoute)
        compose.runOnUiThread { nav.navigate(SettingsRoute) }
        compose.waitForIdle()
        compose.onNode(hasText("Abmelden") and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Abmelden") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(1, auth.logoutCalls)
        assertEquals(listOf("LoginRoute"), stack())
    }

    @Test fun onlineAccountLeadsToTheLoginOfThePortal() {
        show(SetupRoute)
        compose.onNodeWithText("Online-Konto").performClick()
        compose.waitForIdle()
        assertEquals("https://app.bike-maintainer.de/", nav.currentBackStackEntry!!.toRoute<LoginRoute>().serverUrl)
        assertEquals(listOf("SetupRoute", "LoginRoute"), stack())
    }

    @Test fun scannedAddressOfAnOwnServerIsCheckedAndUsed() {
        auth.fetchConfigResult = Outcome.Success(de.bikemaintainer.app.domain.ServerConfig(ServerMode.HOUSEHOLD, null))
        show(SetupRoute)
        compose.onNodeWithText("Eigener Server").performClick()
        compose.waitForIdle()
        assertEquals(true, current().contains("OwnServerRoute"))
        // What the scanner screen hands back (the camera itself is not available in tests).
        compose.runOnIdle { nav.currentBackStackEntry!!.savedStateHandle[SERVER_QR_KEY] = "https://home.example.org/" }
        compose.waitForIdle()
        assertEquals(listOf("https://home.example.org/"), auth.fetchConfigUrls)
        assertEquals("https://home.example.org/", nav.currentBackStackEntry!!.toRoute<ProfilesRoute>().serverUrl)
    }

    @Test fun createAccountAndBackToLogin() {
        show(LoginRoute("https://h/"))
        compose.onNodeWithText("Konto erstellen").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("LoginRoute", "RegisterRoute"), stack())
        compose.onNodeWithText("Zur Anmeldung").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("LoginRoute"), stack())
    }

    @Test fun replayedStatusDoesNotNavigate() {
        session.require(SessionStatus.LOGIN_REQUIRED)
        show(SetupRoute)
        assertEquals(true, current().contains("Setup"))
    }

    @Test fun statusOnUnauthenticatedScreensDoesNotNavigate() {
        show(ProfilesRoute("https://h"))
        session.require(SessionStatus.LOGIN_REQUIRED)
        compose.waitForIdle()
        assertEquals(true, current().contains("Profiles"))
    }

    @Test fun statusPresentAtStartRedirectsFromAuthenticatedScreen() {
        session.require(SessionStatus.PROFILE_SELECTION_REQUIRED)
        show(VehiclesRoute)
        assertEquals(true, current().contains("Profiles"))
        assertEquals(1, stack().size)
    }

    @Test fun statusPresentAtStartRedirectsFromNotificationQuestion() {
        session.require(SessionStatus.LOGIN_REQUIRED)
        show(NotificationPermissionRoute)
        assertEquals(true, current().contains("Login"))
    }

    @Test fun statusChangeWhileComposedNavigates() {
        show(VehiclesRoute)
        session.require(SessionStatus.LOGIN_REQUIRED)
        compose.waitForIdle()
        assertEquals(true, current().contains("Login"))
        assertEquals(1, stack().size)
    }

    private fun openLog() {
        maintenance.tasksResult = Outcome.Success(listOf(task(1, "Ölwechsel", TaskStatus.OVERDUE)))
        show(VehiclesRoute)
        compose.onNodeWithText("Africa Twin").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Wartung erfassen").performClick()
        compose.waitForIdle()
        assertEquals(listOf("VehiclesRoute", "VehicleRoute", "LogMaintenanceRoute"), stack())
    }

    private fun task(id: Long, name: String, status: TaskStatus) =
        MaintenanceTask(id, 7, name, false, true, status, null, null, null, null, null, null)

    @Test fun loggingReturnsToDetailAndReloads() {
        vehicles.updateMileageResult = Outcome.Success(vehicle)
        openLog()
        val calls = vehicles.vehicleCalls.size
        compose.onNodeWithText("Speichern").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("VehiclesRoute", "VehicleRoute"), stack())
        compose.onNodeWithText("Wartung gespeichert.").assertExists()
        assertEquals(1, maintenance.logCalls.size)
        assertEquals(true, vehicles.vehicleCalls.size > calls)
        // Consumed: the message is not shown again when the detail is recreated.
        assertNull(nav.currentBackStackEntry!!.savedStateHandle.get<String>(LOG_RESULT_KEY))
    }

    @Test fun doubleTapOnLogOpensOneScreen() {
        maintenance.tasksResult = Outcome.Success(listOf(task(1, "Ölwechsel", TaskStatus.OVERDUE)))
        show(VehiclesRoute)
        compose.onNodeWithText("Africa Twin").performClick()
        compose.waitForIdle()
        val click = compose.onNodeWithText("Wartung erfassen").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            click()
            click()
        }
        compose.waitForIdle()
        assertEquals(listOf("VehiclesRoute", "VehicleRoute", "LogMaintenanceRoute"), stack())
    }

    @Test fun raiseFailureShowsSavedMileageFailed() {
        vehicles.updateMileageResult = Outcome.Failure(AppError.Network)
        openLog()
        compose.onNode(hasSetTextAction() and hasText("Kilometerstand (km)")).performTextClearance()
        compose.onNode(hasSetTextAction() and hasText("Kilometerstand (km)")).performTextInput("12400")
        compose.onNodeWithText("Speichern").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("VehiclesRoute", "VehicleRoute"), stack())
        compose.onNodeWithText("Wartung gespeichert, aber der Kilometerstand konnte nicht angehoben werden.").assertExists()
        assertEquals(1, maintenance.logCalls.size)
    }

    /** The camera is not available in tests: the photo goes straight to the screen's ViewModel. */
    private fun photographOdometer() {
        compose.onNodeWithContentDescription("Tacho fotografieren").performClick()
        compose.waitForIdle()
        assertEquals(true, current().contains("ScanOdometerRoute"))
        compose.runOnIdle {
            ViewModelProvider(nav.currentBackStackEntry!!)[OdometerViewModel::class.java].onPhoto(GrayImage(400, 300))
        }
        compose.waitForIdle()
        compose.onNodeWithText("Übernehmen").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test fun mileagePhotographedFromTheVehicleGoesIntoTheDialog() {
        show(VehiclesRoute)
        compose.onNodeWithText("Africa Twin").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Kilometerstand eintragen").performClick()
        photographOdometer()
        assertEquals(listOf("VehiclesRoute", "VehicleRoute"), stack())
        // Checked against the vehicle's mileage; in the dialog, not saved yet.
        assertEquals(12345, odometer.reads.single().second)
        compose.onNode(hasSetTextAction() and hasText("12400")).assertExists()
        assertEquals(0, vehicles.updateMileageCalls.size)
    }

    @Test fun mileagePhotographedInTheLogGoesIntoItsField() {
        openLog()
        photographOdometer()
        assertEquals(listOf("VehiclesRoute", "VehicleRoute", "LogMaintenanceRoute"), stack())
        compose.onNode(hasSetTextAction() and hasText("12400")).assertExists()
        assertEquals(0, maintenance.logCalls.size)
    }

    @Test fun backFromLogReturnsToDetailWithoutSaving() {
        openLog()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.waitForIdle()
        assertEquals(listOf("VehiclesRoute", "VehicleRoute"), stack())
        assertEquals(0, maintenance.logCalls.size)
        compose.onNodeWithText("Wartung gespeichert.").assertDoesNotExist()
    }

    @Test fun loginRequiredOnLogMaintenanceRedirects() {
        openLog()
        session.require(SessionStatus.LOGIN_REQUIRED)
        compose.waitForIdle()
        assertEquals(listOf("LoginRoute"), stack())
    }

    @Test fun vehicleIntentOpensDetailOverList() {
        openVehicle.value = 3
        show(VehiclesRoute)
        assertEquals(listOf("VehiclesRoute", "VehicleRoute"), stack())
        assertEquals(3L, nav.currentBackStackEntry?.toRoute<VehicleRoute>()?.vehicleId)
        assertEquals(1, vehicleOpened)
    }

    @Test fun vehicleIntentWhileAppIsRunningOpensDetail() {
        show(VehiclesRoute)
        compose.runOnUiThread { openVehicle.value = 3 }
        compose.waitForIdle()
        assertEquals(listOf("VehiclesRoute", "VehicleRoute"), stack())
        assertEquals(1, vehicleOpened)
    }

    @Test fun vehicleIntentWhenSignedOutStaysOnLogin() {
        openVehicle.value = 3
        show(LoginRoute("https://h"))
        assertEquals(listOf("LoginRoute"), stack())
        assertEquals(1, vehicleOpened)
        assertNull(openVehicle.value)
    }

    @Test fun signInSyncsReminders() {
        auth.deviceLoginResult = Outcome.Success(Unit)
        show(LoginRoute("https://h"))
        compose.onNodeWithText("E-Mail").performTextInput("a@b.de")
        compose.onNodeWithText("Passwort").performTextInput("pw")
        compose.onNodeWithText("Anmelden").performClick()
        compose.waitForIdle()
        assertEquals(listOf("VehiclesRoute"), stack())
        assertEquals(1, reminders.syncCalls)
    }

    @Test fun signInDropsNotificationsOfThePreviousSession() {
        auth.deviceLoginResult = Outcome.Success(Unit)
        show(LoginRoute("https://h", relogin = true))
        compose.onNodeWithText("E-Mail").performTextInput("a@b.de")
        compose.onNodeWithText("Passwort").performTextInput("pw")
        compose.onNodeWithText("Anmelden").performClick()
        compose.waitForIdle()
        assertEquals(1, notifications.cancelAllCalls)
    }
}
