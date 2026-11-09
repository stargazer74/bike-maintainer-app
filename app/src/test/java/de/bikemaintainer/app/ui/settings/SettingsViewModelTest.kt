package de.bikemaintainer.app.ui.settings

import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.Account
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.ServerConfig
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.notifications.FakeDueNotifications
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.FakeReminderScheduler
import de.bikemaintainer.app.ui.MainDispatcherRule
import de.bikemaintainer.app.ui.navigation.LoginRoute
import de.bikemaintainer.app.ui.navigation.ProfilesRoute
import de.bikemaintainer.app.ui.navigation.SetupRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val auth = FakeAuthRepository()
    private val reminders = FakeReminderScheduler()
    private val notifications = FakeDueNotifications()
    private var allowed = true

    private fun SettingsViewModel(auth: FakeAuthRepository) =
        SettingsViewModel(auth, reminders, notifications) { allowed }

    private fun store(mode: ServerMode) {
        auth.settings.value = StoredSettings("https://h", mode, "tok", null)
        auth.fetchConfigResult = Outcome.Success(ServerConfig(mode, "https://site"))
    }

    @Test fun showsServerAccountAndWebsite() {
        store(ServerMode.PORTAL)
        val vm = SettingsViewModel(auth)
        val s = vm.state.value
        assertEquals("https://h", s.serverUrl)
        assertEquals(ServerMode.PORTAL, s.mode)
        assertEquals(Account("max@example.org", null), s.account)
        assertEquals("https://site", s.websiteUrl)
        assertEquals(listOf("https://h"), auth.fetchConfigUrls)
    }

    @Test fun websiteLinksMissingWithoutWebsiteUrl() {
        store(ServerMode.PORTAL)
        auth.fetchConfigResult = Outcome.Success(ServerConfig(ServerMode.PORTAL, null))
        assertNull(SettingsViewModel(auth).state.value.websiteUrl)
        auth.fetchConfigResult = Outcome.Failure(AppError.Network)
        assertNull(SettingsViewModel(auth).state.value.websiteUrl)
    }

    @Test fun logoutReturnsToLoginForPortal() {
        store(ServerMode.PORTAL)
        val vm = SettingsViewModel(auth)
        vm.logout()
        assertEquals(1, auth.logoutCalls)
        assertEquals(LoginRoute("https://h"), vm.state.value.next)
    }

    @Test fun logoutReturnsToProfilesForHousehold() {
        store(ServerMode.HOUSEHOLD)
        val vm = SettingsViewModel(auth)
        vm.logout()
        assertEquals(ProfilesRoute("https://h"), vm.state.value.next)
    }

    @Test fun switchServerReturnsToSetup() {
        store(ServerMode.PORTAL)
        val vm = SettingsViewModel(auth)
        vm.switchServer()
        assertEquals(1, auth.switchServerCalls)
        assertEquals(SetupRoute, vm.state.value.next)
        assertFalse(vm.state.value.busy)
    }

    @Test fun logoutFailureStillLeavesSettings() {
        store(ServerMode.PORTAL)
        auth.logoutResult = Outcome.Failure(AppError.Network)
        val vm = SettingsViewModel(auth)
        vm.logout()
        assertEquals(LoginRoute("https://h"), vm.state.value.next)
    }

    @Test fun toggleSavesAndSyncs() {
        store(ServerMode.PORTAL)
        val vm = SettingsViewModel(auth)
        assertTrue(vm.state.value.notificationsEnabled)
        vm.setNotificationsEnabled(false)
        assertFalse(auth.settings.value.notificationsEnabled)
        assertFalse(vm.state.value.notificationsEnabled)
        assertEquals(1, reminders.syncCalls)
        vm.setNotificationsEnabled(true)
        assertTrue(vm.state.value.notificationsEnabled)
        assertEquals(2, reminders.syncCalls)
    }

    @Test fun blockedWhenEnabledButNotAllowed() {
        store(ServerMode.PORTAL)
        allowed = false
        val vm = SettingsViewModel(auth)
        assertTrue(vm.state.value.notificationsBlocked)
        vm.setNotificationsEnabled(false)
        assertFalse(vm.state.value.notificationsBlocked)
        vm.setNotificationsEnabled(true)
        assertTrue(vm.state.value.notificationsBlocked)
        allowed = true
        vm.refreshPermission()
        assertFalse(vm.state.value.notificationsBlocked)
    }

    @Test fun logoutCancelsRemindersAndNotifications() {
        store(ServerMode.PORTAL)
        SettingsViewModel(auth).logout()
        assertEquals(1, reminders.cancelCalls)
        assertEquals(1, notifications.cancelAllCalls)
    }

    @Test fun switchServerCancelsRemindersAndNotifications() {
        store(ServerMode.PORTAL)
        SettingsViewModel(auth).switchServer()
        assertEquals(1, reminders.cancelCalls)
        assertEquals(1, notifications.cancelAllCalls)
    }

    @Test fun grantedPermissionSyncsOnceOnRefresh() {
        store(ServerMode.PORTAL)
        allowed = false
        val vm = SettingsViewModel(auth)
        vm.refreshPermission()
        assertEquals(0, reminders.syncCalls)
        allowed = true
        vm.refreshPermission()
        assertEquals(1, reminders.syncCalls)
        vm.refreshPermission()
        assertEquals(1, reminders.syncCalls)
        allowed = false
        vm.refreshPermission()
        assertEquals(2, reminders.syncCalls)
    }
}
