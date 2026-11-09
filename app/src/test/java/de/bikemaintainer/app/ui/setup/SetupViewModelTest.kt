package de.bikemaintainer.app.ui.setup

import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.ServerConfig
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.MainDispatcherRule
import de.bikemaintainer.app.ui.navigation.LoginRoute
import de.bikemaintainer.app.ui.navigation.ProfilesRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SetupViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val auth = FakeAuthRepository()
    private val viewModel = SetupViewModel(auth)

    @Test
    fun defaultsToTheOfficialServer() {
        assertEquals("https://app.bike-maintainer.de", viewModel.state.value.address)
    }

    @Test
    fun portalServerLeadsToLogin() {
        viewModel.onAddressChange("app.bike-maintainer.de ")
        viewModel.onContinue()
        assertEquals(listOf("https://app.bike-maintainer.de/"), auth.fetchConfigUrls)
        assertEquals(LoginRoute("https://app.bike-maintainer.de/"), viewModel.state.value.next)
        viewModel.onNavigated()
        assertNull(viewModel.state.value.next)
    }

    @Test
    fun householdServerLeadsToProfiles() {
        auth.fetchConfigResult = Outcome.Success(ServerConfig(ServerMode.HOUSEHOLD, null))
        viewModel.onContinue()
        assertEquals(ProfilesRoute("https://app.bike-maintainer.de/"), viewModel.state.value.next)
    }

    @Test
    fun cleartextAddressAsksBeforeConnecting() {
        viewModel.onAddressChange("http://192.168.178.20:8080")
        viewModel.onContinue()
        assertTrue(viewModel.state.value.confirmCleartext)
        assertEquals(0, auth.fetchConfigCalls)
        viewModel.onCleartextConfirmed()
        assertFalse(viewModel.state.value.confirmCleartext)
        assertEquals(listOf("http://192.168.178.20:8080/"), auth.fetchConfigUrls)
    }

    @Test
    fun dismissingTheCleartextHintDoesNotConnect() {
        viewModel.onAddressChange("http://192.168.178.20:8080")
        viewModel.onContinue()
        viewModel.onCleartextDismissed()
        assertFalse(viewModel.state.value.confirmCleartext)
        assertEquals(0, auth.fetchConfigCalls)
    }

    @Test
    fun unreachableServerShowsError() {
        auth.fetchConfigResult = Outcome.Failure(AppError.Network)
        viewModel.onContinue()
        assertEquals(SetupError.NOT_REACHABLE, viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)
        assertNull(viewModel.state.value.next)
    }

    @Test
    fun nonBikeMaintainerShowsError() {
        auth.fetchConfigResult = Outcome.Failure(AppError.Http(404, null))
        viewModel.onContinue()
        assertEquals(SetupError.NOT_BIKE_MAINTAINER, viewModel.state.value.error)
    }

    @Test
    fun serverErrorMeansNotReachable() {
        for (status in listOf(500, 502, 503)) {
            auth.fetchConfigResult = Outcome.Failure(AppError.Http(status, null))
            viewModel.onContinue()
            assertEquals("$status", SetupError.NOT_REACHABLE, viewModel.state.value.error)
        }
        auth.fetchConfigResult = Outcome.Failure(AppError.Http(-1, null)) // 2xx with a foreign body
        viewModel.onContinue()
        assertEquals(SetupError.NOT_BIKE_MAINTAINER, viewModel.state.value.error)
    }

    @Test
    fun cleartextToPublicAddressShowsError() {
        auth.fetchConfigResult = Outcome.Failure(AppError.CleartextNotAllowed)
        viewModel.onAddressChange("http://example.org")
        viewModel.onContinue()
        viewModel.onCleartextConfirmed()
        assertEquals(SetupError.CLEARTEXT_NOT_ALLOWED, viewModel.state.value.error)
    }

    @Test
    fun invalidAddressShowsErrorWithoutRequest() {
        viewModel.onAddressChange("ftp://x")
        viewModel.onContinue()
        assertEquals(SetupError.INVALID_ADDRESS, viewModel.state.value.error)
        assertEquals(0, auth.fetchConfigCalls)
    }

    @Test
    fun editingClearsTheError() {
        viewModel.onAddressChange("")
        viewModel.onContinue()
        viewModel.onAddressChange("x")
        assertNull(viewModel.state.value.error)
    }
}
