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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsNotEnabled
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.notifications.FakeDueNotifications
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.FakeReminderScheduler
import de.bikemaintainer.app.ui.MainDispatcherRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.onAllNodesWithText
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

    @Test fun logoutWarnsAboutEntriesNotSentYet() {
        val outbox = de.bikemaintainer.app.data.storage.InMemoryOutbox(
            listOf(de.bikemaintainer.app.data.storage.OutboxEntry("a", 3, 0, mileage = 1)),
        )
        val pending = de.bikemaintainer.app.data.repository.PendingRepository(outbox)
        val vm = SettingsViewModel(auth, reminders, FakeDueNotifications(), pending) { allowed }
        compose.setContent { SettingsScreen(viewModel = vm, onBack = {}, onNavigate = {}) }
        compose.onAllNodesWithText("Abmelden")[0].performScrollTo().performClick()
        compose.onNodeWithText("1 noch nicht übertragener Eintrag geht dabei verloren.").assertExists()
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

    @Test fun deleteAccountAsksTwiceBeforeDeleting() {
        show()
        compose.onNodeWithText("Konto löschen").performScrollTo().performClick()
        compose.onNodeWithText("Weiter").assertIsNotEnabled()
        compose.onNodeWithText("Passwort").performTextInput("geheim-1234")
        compose.onNodeWithText("Weiter").performClick()
        compose.onNodeWithText("Wirklich löschen?").assertExists()
        compose.onNodeWithText("Das Konto max@example.org und alle seine Daten werden jetzt endgültig gelöscht.")
            .assertExists()
        assertEquals(emptyList<String>(), auth.deleteAccountCalls)
        compose.onNodeWithText("Endgültig löschen").performClick()
        compose.waitForIdle()
        assertEquals(listOf("geheim-1234"), auth.deleteAccountCalls)
    }

    @Test fun deleteAccountBackOnTheSecondStepDeletesNothing() {
        show()
        compose.onNodeWithText("Konto löschen").performScrollTo().performClick()
        compose.onNodeWithText("Passwort").performTextInput("geheim-1234")
        compose.onNodeWithText("Weiter").performClick()
        compose.onNodeWithText("Zurück").performClick()
        compose.onNodeWithText("Bitte bestätige mit deinem Passwort.", substring = true).assertExists()
        compose.onNodeWithText("Abbrechen").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Wirklich löschen?").assertDoesNotExist()
        assertEquals(emptyList<String>(), auth.deleteAccountCalls)
    }

    @Test fun wrongPasswordShowsTheFirstStepAgain() {
        auth.deleteAccountResult = Outcome.Failure(AppError.Http(403, "WRONG_PASSWORD"))
        show()
        compose.onNodeWithText("Konto löschen").performScrollTo().performClick()
        compose.onNodeWithText("Passwort").performTextInput("falsch")
        compose.onNodeWithText("Weiter").performClick()
        compose.onNodeWithText("Endgültig löschen").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Das Passwort ist falsch.").assertExists()
        compose.onNodeWithText("Weiter").assertIsNotEnabled()
    }

    @Test fun householdHasNoDeleteAccount() {
        auth.settings.value = StoredSettings("https://h", ServerMode.HOUSEHOLD, null, 3)
        show()
        compose.onNodeWithText("Konto löschen").assertDoesNotExist()
    }
}
