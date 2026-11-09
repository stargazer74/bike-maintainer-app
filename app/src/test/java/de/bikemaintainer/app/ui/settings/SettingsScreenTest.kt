package de.bikemaintainer.app.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.notifications.FakeDueNotifications
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.FakeReminderScheduler
import de.bikemaintainer.app.ui.MainDispatcherRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de")
class SettingsScreenTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val auth = FakeAuthRepository().also {
        it.settings.value = StoredSettings("https://h", ServerMode.PORTAL, "tok", null)
    }
    private val reminders = FakeReminderScheduler()
    private var allowed = true

    private fun show() {
        val vm = SettingsViewModel(auth, reminders, FakeDueNotifications()) { allowed }
        compose.setContent { SettingsScreen(viewModel = vm, onBack = {}, onNavigate = {}) }
    }

    private val label = "Erinnerungen an fällige Wartungen"
    private val isSwitch = SemanticsMatcher("is a switch") {
        SemanticsProperties.Role in it.config && it.config[SemanticsProperties.Role] == Role.Switch
    }

    @Test fun switchRowIsOneNodeWithSwitchRole() {
        show()
        compose.onNode(isSwitch).assertIsOn()
        compose.onNodeWithText(label).assertExists()
        compose.onNode(isSwitch).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(isSwitch).assertIsOff()
        assertFalse(auth.settings.value.notificationsEnabled)
        assertEquals(1, reminders.syncCalls)
    }

    @Test fun blockedShowsTextAndButton() {
        allowed = false
        show()
        compose.onNodeWithText("Benachrichtigungen sind in den Systemeinstellungen ausgeschaltet.").assertExists()
        compose.onNodeWithText("Systemeinstellungen öffnen").assertExists()
    }

    @Test fun notBlockedShowsNeither() {
        show()
        compose.onNodeWithText("Systemeinstellungen öffnen").assertDoesNotExist()
    }

    @After fun resetTheme() = AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    @Test fun themeOptionsSelectTheNightMode() {
        show()
        compose.onNodeWithText("Design").assertExists()
        compose.onNodeWithText("Dunkel").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())
        compose.onNodeWithText("Hell").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())
    }

    @Test fun themeOptionShowsTheCurrentMode() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        show()
        compose.onNodeWithText("Dunkel").assertIsSelected()
    }
}
