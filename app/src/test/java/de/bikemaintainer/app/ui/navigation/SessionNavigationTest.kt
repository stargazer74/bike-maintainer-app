package de.bikemaintainer.app.ui.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.compose.ui.platform.LocalContext
import de.bikemaintainer.app.data.network.SessionState
import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.ui.FakeAuthRepository
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.navigation.compose.ComposeNavigator

@RunWith(RobolectricTestRunner::class)
class SessionNavigationTest {
    @get:Rule val compose = createComposeRule()

    private val auth = FakeAuthRepository().also {
        it.settings.value = StoredSettings("https://h", ServerMode.PORTAL, "tok", null, notificationsAsked = true)
    }
    private lateinit var nav: TestNavHostController
    private var onSettingsBack: () -> Unit = {}

    private fun show(start: Any, session: SessionState = SessionState(), afterSignIn: Boolean = false) {
        compose.setContent {
            nav = TestNavHostController(LocalContext.current).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { nav.redirectOnSessionChanges(session.status, auth.settings) }
            NavHost(nav, startDestination = start) {
                composable<SetupRoute> {}
                composable<LoginRoute> { LaunchedEffect(Unit) { if (afterSignIn) scope.launch { nav.navigateAfterSignIn(auth) } } }
                composable<ProfilesRoute> {}
                composable<VehiclesRoute> {}
                composable<NotificationPermissionRoute> {}
                composable<SettingsRoute> { val back = nav.popBackOnce(); onSettingsBack = back }
            }
        }
        compose.waitForIdle()
    }

    private fun stack() = nav.currentBackStack.value.mapNotNull { it.destination.route?.substringBefore('?')?.substringAfterLast('.') }
        .filter { !it.startsWith("androidx") }

    @Test fun backStackIsOnlyVehiclesAfterLogoutAndLogin() {
        show(VehiclesRoute, afterSignIn = true)
        compose.runOnUiThread { nav.navigate(LoginRoute("https://h")) { popUpTo(0) } }
        compose.waitForIdle()
        compose.waitForIdle()
        assertEquals(1, stack().count { it.contains("Vehicles") })
        assertEquals(0, stack().count { it.contains("Login") })
        assertEquals(1, stack().size)
    }

    @Test fun notificationQuestionReplacesWholeStack() {
        auth.settings.value = auth.settings.value.copy(notificationsAsked = false)
        // Robolectric default SDK >= 33 for this project's targetSdk.
        show(LoginRoute("https://h"), afterSignIn = true)
        compose.waitForIdle()
        val s = stack()
        assertEquals(1, s.size)
        assertEquals(true, s.single().contains("Notification"))
    }

    @Test fun settingsBackPopsOnlyOnce() {
        show(VehiclesRoute)
        compose.runOnUiThread { nav.navigate(SettingsRoute) }
        compose.waitForIdle()
        compose.runOnUiThread {
            onSettingsBack()
            onSettingsBack() // double tap: must not pop Vehicles as well
        }
        compose.waitForIdle()
        assertEquals(listOf("VehiclesRoute"), stack())
    }
}
