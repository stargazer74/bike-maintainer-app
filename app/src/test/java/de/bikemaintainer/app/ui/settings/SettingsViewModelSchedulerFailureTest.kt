package de.bikemaintainer.app.ui.settings

import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.notifications.FakeDueNotifications
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.FakeReminderScheduler
import de.bikemaintainer.app.ui.MainDispatcherRule
import de.bikemaintainer.app.ui.navigation.LoginRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric: the quiet wrappers log through android.util.Log. */
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelSchedulerFailureTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun failingSchedulerDoesNotCrashToggleOrLogout() {
        val auth = FakeAuthRepository().also { it.settings.value = StoredSettings("https://h", ServerMode.PORTAL, "tok", null) }
        val reminders = FakeReminderScheduler().also { it.failWith = IllegalStateException("WorkManager") }
        val notifications = FakeDueNotifications()
        val vm = SettingsViewModel(auth, reminders, notifications) { true }
        vm.setNotificationsEnabled(false)
        assertFalse(vm.state.value.notificationsEnabled)
        assertEquals(1, reminders.syncCalls)
        vm.logout()
        assertEquals(LoginRoute("https://h"), vm.state.value.next)
        assertEquals(1, reminders.cancelCalls)
        assertEquals(1, notifications.cancelAllCalls)
    }
}
