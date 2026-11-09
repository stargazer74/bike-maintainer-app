package de.bikemaintainer.app.ui.navigation

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import de.bikemaintainer.app.data.network.SessionState
import de.bikemaintainer.app.data.network.SessionStatus
import de.bikemaintainer.app.data.storage.StoredSettings
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
        compose.runOnUiThread { nav.popBackStack() }
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
        compose.onAllNodesWithText("Abmelden").onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Abmelden").onLast().performClick()
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
}
