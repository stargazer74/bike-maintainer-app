package de.bikemaintainer.app.ui.navigation

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.FakeMaintenanceRepository
import de.bikemaintainer.app.ui.FakeVehicleRepository
import de.bikemaintainer.app.ui.MainDispatcherRule
import java.time.LocalDate
import org.junit.Assert.assertEquals
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
    private lateinit var nav: TestNavHostController

    private fun show(start: Any) {
        compose.setContent {
            nav = TestNavHostController(LocalContext.current).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
            AppNavHost(AppDependencies(auth, vehicles, maintenance, session.status), start, nav)
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
        compose.onNode(hasText("Abmelden") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Abmelden") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(1, auth.logoutCalls)
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
}
