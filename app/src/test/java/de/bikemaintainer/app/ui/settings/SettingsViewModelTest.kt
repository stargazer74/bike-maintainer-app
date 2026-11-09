package de.bikemaintainer.app.ui.settings

import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.Account
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.ServerConfig
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.MainDispatcherRule
import de.bikemaintainer.app.ui.navigation.LoginRoute
import de.bikemaintainer.app.ui.navigation.ProfilesRoute
import de.bikemaintainer.app.ui.navigation.SetupRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val auth = FakeAuthRepository()

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
}
