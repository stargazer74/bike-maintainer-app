package de.bikemaintainer.app.ui.vehicles

import de.bikemaintainer.app.domain.Access
import de.bikemaintainer.app.domain.AccessStatus
import de.bikemaintainer.app.domain.Account
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleSummary
import de.bikemaintainer.app.domain.VehicleType
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.FakeVehicleRepository
import de.bikemaintainer.app.ui.MainDispatcherRule
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VehiclesViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val repo = FakeVehicleRepository()
    private val summary = VehicleSummary(
        Vehicle(7, "Africa Twin", VehicleType.MOTORCYCLE, null, null, 2020, LocalDate.of(2020, 5, 1), 12345),
        TaskStatus.OVERDUE,
        2,
    )

    private val auth = FakeAuthRepository()

    private fun viewModel() = VehiclesViewModel(repo, auth)

    @Test fun loadsSummariesOnStart() {
        repo.summariesResult = Outcome.Success(listOf(summary))
        val state = viewModel().state.value
        assertEquals(listOf(summary), state.summaries)
        assertNull(state.error)
    }

    @Test fun failureWithoutDataShowsError() {
        repo.summariesResult = Outcome.Failure(AppError.Network)
        val state = viewModel().state.value
        assertNull(state.summaries)
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
        assertEquals(2, repo.summariesCalls) // the reload is really in flight
        assertEquals(listOf(summary), vm.state.value.summaries)
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

    @Test fun silentReloadFailureWithoutDataSetsError() {
        repo.summariesResult = Outcome.Failure(AppError.Network)
        val vm = viewModel()
        repo.summariesResult = Outcome.Failure(AppError.Http(503, null))
        vm.reloadSilently()
        assertNull(vm.state.value.summaries)
        assertEquals(AppError.Http(503, null), vm.state.value.error)
    }

    @Test fun successfulReloadClearsError() {
        repo.summariesResult = Outcome.Success(listOf(summary))
        val vm = viewModel()
        repo.summariesResult = Outcome.Failure(AppError.Network)
        vm.refresh()
        assertEquals(AppError.Network, vm.state.value.error)
        repo.summariesResult = Outcome.Success(listOf(summary))
        vm.reloadSilently()
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

    @Test fun firstResumeOnlyMarksLaterResumesReloadSilently() {
        repo.summariesResult = Outcome.Success(listOf(summary))
        val vm = viewModel()
        vm.onResume()
        assertEquals(1, repo.summariesCalls)
        repo.summariesResult = Outcome.Failure(AppError.Network)
        vm.onResume()
        assertEquals(2, repo.summariesCalls)
        assertEquals(listOf(summary), vm.state.value.summaries)
        assertNull(vm.state.value.error) // silent
    }

    @Test fun refreshDuringSilentReloadBecomesVisible() {
        repo.summariesResult = Outcome.Success(listOf(summary))
        val vm = viewModel()
        repo.summariesGate = CompletableDeferred()
        repo.summariesResult = Outcome.Failure(AppError.Network)
        vm.reloadSilently()
        vm.refresh()
        assertEquals(2, repo.summariesCalls)
        assertTrue(vm.state.value.refreshing)
        repo.summariesGate!!.complete(Unit)
        assertFalse(vm.state.value.refreshing)
        assertEquals(AppError.Network, vm.state.value.error)
    }

    @Test fun accessComesFromTheServer() {
        auth.currentAccountResult = Outcome.Success(Account("a@b.de", null, Access(AccessStatus.EXPIRED)))
        val vm = viewModel()
        assertEquals(AccessStatus.EXPIRED, vm.state.value.access.status)
        assertEquals(1, auth.currentAccountCalls)
    }
}
