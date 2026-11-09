package de.bikemaintainer.app.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import de.bikemaintainer.app.domain.Profile
import de.bikemaintainer.app.ui.login.LoginContent
import de.bikemaintainer.app.ui.login.LoginError
import de.bikemaintainer.app.ui.login.LoginUiState
import de.bikemaintainer.app.ui.profiles.ProfilesContent
import de.bikemaintainer.app.ui.profiles.ProfilesError
import de.bikemaintainer.app.ui.profiles.ProfilesUiState
import de.bikemaintainer.app.ui.settings.SettingsScreen
import de.bikemaintainer.app.ui.settings.SettingsViewModel
import de.bikemaintainer.app.ui.setup.SetupContent
import de.bikemaintainer.app.ui.setup.SetupError
import de.bikemaintainer.app.ui.setup.SetupUiState
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccessibilityTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val politeLiveRegion = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test fun setupButtonKeepsItsLabelWhileLoading() {
        compose.setContent {
            SetupContent(SetupUiState(loading = true), {}, {}, {}, {})
        }
        compose.onNodeWithText("Continue").assertExists()
        compose.onNodeWithContentDescription("Loading…").assertExists()
    }

    @Test fun setupErrorIsALiveRegion() {
        compose.setContent {
            SetupContent(SetupUiState(error = SetupError.INVALID_ADDRESS), {}, {}, {}, {})
        }
        compose.onNodeWithText("Please enter a valid address.").assert(politeLiveRegion)
    }

    @Test fun loginErrorIsALiveRegionAndSpinnerIsDescribed() {
        compose.setContent {
            LoginContent(
                state = LoginUiState(deviceName = "", reloginHint = false, loading = true, error = LoginError.STORAGE),
                onEmailChange = {}, onPasswordChange = {}, onDeviceNameChange = {}, onSubmit = {},
                onForgotPassword = {}, onOtherServer = {},
            )
        }
        compose.onNodeWithText("Could not save the login on this device.").assert(politeLiveRegion)
        compose.onNodeWithContentDescription("Loading…").assertExists()
    }

    @Test fun profileCardsAreButtonsAndErrorsAreLiveRegions() {
        compose.setContent {
            ProfilesContent(
                state = ProfilesUiState(profiles = listOf(Profile(1, "Max")), loading = false, error = ProfilesError.STORAGE),
                onSelect = {}, onRetry = {}, onOpenWebApp = {}, onOtherServer = {},
            )
        }
        compose.onNodeWithText("Could not save the login on this device.").assert(politeLiveRegion)
        // The clickable card merges its text, so the button node itself carries "Max".
        compose.onNode(hasText("Max").and(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)))
            .assertExists()
    }

    @Test fun settingsShowProgressWhileLoggingOut() {
        val auth = FakeAuthRepository().apply { logoutGate = CompletableDeferred() }
        val vm = SettingsViewModel(auth, FakeReminderScheduler(), de.bikemaintainer.app.notifications.FakeDueNotifications()) { true }
        compose.setContent { SettingsScreen(viewModel = vm, onBack = {}, onNavigate = {}) }
        compose.onNodeWithContentDescription("Loading…").assertDoesNotExist()
        vm.logout()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Loading…").assertExists()
    }
}
