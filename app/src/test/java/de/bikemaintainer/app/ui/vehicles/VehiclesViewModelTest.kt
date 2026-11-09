package de.bikemaintainer.app.ui.vehicles

import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleSummary
import de.bikemaintainer.app.domain.VehicleType
import de.bikemaintainer.app.ui.FakeVehicleRepository
import de.bikemaintainer.app.ui.MainDispatcherRule
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VehiclesViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val repo = FakeVehicleRepository()
    private val settings = MutableStateFlow(
        StoredSettings(serverUrl = "https://h", mode = null, encryptedDeviceToken = null, profileId = null),
    )
    private val summary = VehicleSummary(
        Vehicle(7, "Africa Twin", VehicleType.MOTORCYCLE, null, null, 2020, LocalDate.of(2020, 5, 1), 12345),
        TaskStatus.OVERDUE,
        2,
    )

    private fun viewModel() = VehiclesViewModel(repo, settings)

    @Test fun loadsSummariesOnStart() {
        repo.summariesResult = Outcome.Success(listOf(summary))
        val state = viewModel().state.value
        assertEquals(listOf(summary), state.summaries)
        assertFalse(state.loading)
        assertNull(state.error)
    }

    @Test fun failureWithoutDataShowsError() {
        repo.summariesResult = Outcome.Failure(AppError.Network)
        val state = viewModel().state.value
        assertNull(state.summaries)
        assertFalse(state.loading)
        assertEquals(AppError.Network, state.error)
    }

    @Test fun retryAfterFailureLoadsAgain() {
        repo.summariesResult = Outcome.Failure(AppError.Network)
        val vm = viewModel()
        repo.summariesResult = Outcome.Success(listOf(summary))
        vm.refresh()
        assertEquals(listOf(summary), vm.state.value.summaries)
        assertNull(vm.state.value.error)
        assertEquals(2, repo.summariesCalls)
    }

    @Test fun refreshFailureKeepsData() {
        repo.summariesResult = Outcome.Success(listOf(summary))
        val vm = viewModel()
        repo.summariesResult = Outcome.Failure(AppError.Network)
        vm.refresh()
        assertEquals(listOf(summary), vm.state.value.summaries)
        assertEquals(AppError.Network, vm.state.value.error)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun silentReloadDoesNotShowSpinner() {
        repo.summariesResult = Outcome.Success(listOf(summary))
        val vm = viewModel()
        repo.summariesGate = CompletableDeferred()
        vm.reloadSilently()
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.refreshing)
        repo.summariesGate!!.complete(Unit)
        assertEquals(2, repo.summariesCalls)
    }

    @Test fun silentReloadFailureKeepsDataWithoutError() {
        repo.summariesResult = Outcome.Success(listOf(summary))
        val vm = viewModel()
        repo.summariesResult = Outcome.Failure(AppError.Network)
        vm.reloadSilently()
        assertEquals(listOf(summary), vm.state.value.summaries)
        assertNull(vm.state.value.error)
    }

    @Test fun refreshShowsSpinnerWhileLoading() {
        repo.summariesResult = Outcome.Success(listOf(summary))
        val vm = viewModel()
        repo.summariesGate = CompletableDeferred()
        vm.refresh()
        assertTrue(vm.state.value.refreshing)
        repo.summariesGate!!.complete(Unit)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun concurrentRefreshIsIgnored() {
        repo.summariesGate = CompletableDeferred()
        val vm = viewModel()
        vm.refresh()
        vm.refresh()
        vm.reloadSilently()
        assertEquals(1, repo.summariesCalls)
        repo.summariesGate!!.complete(Unit)
    }

    @Test fun webAppUrlComesFromSettings() {
        assertEquals("https://h", viewModel().state.value.webAppUrl)
    }
}
