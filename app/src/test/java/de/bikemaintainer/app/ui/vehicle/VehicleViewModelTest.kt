package de.bikemaintainer.app.ui.vehicle

import de.bikemaintainer.app.domain.Access
import de.bikemaintainer.app.domain.AccessStatus
import de.bikemaintainer.app.domain.Account
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleType
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.FakeMaintenanceRepository
import de.bikemaintainer.app.ui.FakeMeasurementRepository
import de.bikemaintainer.app.ui.FakeVehicleRepository
import de.bikemaintainer.app.ui.MainDispatcherRule
import de.bikemaintainer.app.ui.common.isNotFound
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VehicleViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val vehicles = FakeVehicleRepository()
    private val maintenance = FakeMaintenanceRepository()
    private val measurements = FakeMeasurementRepository()
    private val vehicle = Vehicle(7, "Africa Twin", VehicleType.MOTORCYCLE, null, null, 2020, LocalDate.of(2020, 5, 1), 12345)

    private fun task(id: Long, status: TaskStatus, active: Boolean = true) =
        MaintenanceTask(id, 7, "T$id", false, active, status, null, null, null, null, null, null)

    private val auth = FakeAuthRepository()

    private val cancelled = mutableListOf<Long>()

    private fun viewModel(): VehicleViewModel {
        if (vehicles.vehicleResult == null) vehicles.vehicleResult = Outcome.Success(vehicle)
        return VehicleViewModel(7, vehicles, maintenance, measurements, auth) { cancelled += it }
    }

    @Test fun deletingRemovesTheNotificationOfTheVehicle() {
        val vm = viewModel()
        vm.deleteVehicle()
        assertEquals(listOf(7L), cancelled)
    }

    @Test fun nothingCanBeRecordedWhileTheDeleteRuns() {
        vehicles.deleteVehicleGate = CompletableDeferred()
        val vm = viewModel()
        assertTrue(vm.state.value.canRecord)
        vm.deleteVehicle()
        assertFalse(vm.state.value.canRecord)
        vm.openMileageDialog()
        assertNull(vm.state.value.mileageDialog)
        vehicles.deleteVehicleGate!!.complete(Unit)
        assertTrue(vm.state.value.deleted)
    }

    @Test fun startsWithTheLastKnownAccess() {
        auth.access.value = Access(AccessStatus.EXPIRED)
        auth.currentAccountResult = Outcome.Failure(AppError.Network)
        val vm = viewModel()
        assertFalse(vm.state.value.canRecord)
        assertEquals(AccessStatus.EXPIRED, vm.state.value.access.status)
    }

    @Test fun switchedOffTasksAreListedApart() {
        maintenance.tasksResult = Outcome.Success(
            listOf(task(1, TaskStatus.OK), task(2, TaskStatus.OK, active = false).copy(name = "b"), task(3, TaskStatus.OK, active = false).copy(name = "A")),
        )
        val state = viewModel().state.value
        assertEquals(listOf(1L), state.tasks.map { it.id })
        assertEquals(listOf(3L, 2L), state.inactiveTasks.map { it.id })
    }

    @Test fun deletingTheVehicleLeaves() {
        val vm = viewModel()
        vm.deleteVehicle()
        assertEquals(listOf(7L), vehicles.deleteVehicleCalls)
        assertTrue(vm.state.value.deleted)
    }

    @Test fun deletingWorksOnAReadOnlyAccount() {
        auth.currentAccountResult = Outcome.Success(Account("a@b.de", null, Access(AccessStatus.EXPIRED)))
        val vm = viewModel()
        vm.deleteVehicle()
        assertTrue(vm.state.value.deleted)
    }

    @Test fun failedDeleteStaysWithTheError() {
        vehicles.deleteVehicleResult = Outcome.Failure(AppError.Network)
        val vm = viewModel()
        vm.deleteVehicle()
        assertFalse(vm.state.value.deleted)
        assertEquals(AppError.Network, vm.state.value.deleteError)
        assertEquals("Africa Twin", vm.state.value.vehicle?.name)
    }

    @Test fun vehicleThatIsAlreadyGoneCountsAsDeleted() {
        vehicles.deleteVehicleResult = Outcome.Failure(AppError.NotFound)
        val vm = viewModel()
        vm.deleteVehicle()
        assertTrue(vm.state.value.deleted)
    }

    @Test fun asksTheServerForTheAccess() {
        auth.currentAccountResult = Outcome.Success(Account("a@b.de", null, Access(AccessStatus.TRIAL)))
        val vm = viewModel()
        assertEquals(AccessStatus.TRIAL, vm.state.value.access.status)
        assertEquals(1, auth.currentAccountCalls)
    }

    @Test fun readOnlyAccountCannotEnterTheMileage() {
        auth.currentAccountResult = Outcome.Success(Account("a@b.de", null, Access(AccessStatus.EXPIRED)))
        val vm = openDialog()
        assertFalse(vm.state.value.access.canWrite)
        assertNull(vm.state.value.mileageDialog)
    }

    @Test fun refusedWriteMakesTheAccountReadOnly() {
        vehicles.updateMileageResult = Outcome.Failure(AppError.Http(403, "SUBSCRIPTION_REQUIRED"))
        val vm = openDialog()
        vm.onMileageInput("12400")
        vm.saveMileage()
        assertEquals(1, auth.subscriptionRequiredCalls)
        assertFalse(vm.state.value.access.canWrite)
        assertEquals(
            MileageError.Save(AppError.Http(403, "SUBSCRIPTION_REQUIRED")),
            vm.state.value.mileageDialog!!.error,
        )
    }

    @Test fun loadsVehicleAndSortedTasks() {
        maintenance.tasksResult = Outcome.Success(
            listOf(task(1, TaskStatus.OK), task(2, TaskStatus.OVERDUE), task(3, TaskStatus.DUE_SOON, active = false)),
        )
        val state = viewModel().state.value
        assertEquals(vehicle, state.vehicle)
        assertEquals(listOf(2L, 1L), state.tasks.map { it.id })
        assertNull(state.error)
        assertEquals(listOf(7L), vehicles.vehicleCalls)
        assertEquals(listOf(7L), maintenance.tasksCalls)
    }

    @Test fun deletedVehicleShowsNotFound() {
        vehicles.vehicleResult = Outcome.Failure(AppError.NotFound)
        val state = viewModel().state.value
        assertNull(state.vehicle)
        assertTrue(state.error!!.isNotFound())
    }

    @Test fun taskFailureShowsError() {
        maintenance.tasksResult = Outcome.Failure(AppError.Network)
        val state = viewModel().state.value
        assertNull(state.vehicle)
        assertEquals(AppError.Network, state.error)
    }

    @Test fun firstErrorWins() {
        vehicles.vehicleResult = Outcome.Failure(AppError.NotFound)
        maintenance.tasksResult = Outcome.Failure(AppError.Network)
        assertEquals(AppError.NotFound, viewModel().state.value.error)
    }

    @Test fun refreshFailureKeepsData() {
        maintenance.tasksResult = Outcome.Success(listOf(task(1, TaskStatus.OK)))
        val vm = viewModel()
        maintenance.tasksResult = Outcome.Failure(AppError.Network)
        vm.refresh()
        assertEquals(vehicle, vm.state.value.vehicle)
        assertEquals(1, vm.state.value.tasks.size)
        assertEquals(AppError.Network, vm.state.value.error)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun retryAfterFailureLoads() {
        maintenance.tasksResult = Outcome.Failure(AppError.Network)
        val vm = viewModel()
        maintenance.tasksResult = Outcome.Success(listOf(task(1, TaskStatus.OK)))
        vm.refresh()
        assertEquals(vehicle, vm.state.value.vehicle)
        assertNull(vm.state.value.error)
    }

    @Test fun silentReloadFailureKeepsDataWithoutError() {
        val vm = viewModel()
        maintenance.tasksResult = Outcome.Failure(AppError.Network)
        vm.reloadSilently()
        assertEquals(vehicle, vm.state.value.vehicle)
        assertNull(vm.state.value.error)
    }

    @Test fun silentReloadFailureWithoutDataSetsError() {
        maintenance.tasksResult = Outcome.Failure(AppError.Network)
        val vm = viewModel()
        maintenance.tasksResult = Outcome.Failure(AppError.Http(503, null))
        vm.reloadSilently()
        assertEquals(AppError.Http(503, null), vm.state.value.error)
    }

    @Test fun refreshShowsSpinnerWhileLoading() {
        val vm = viewModel()
        maintenance.tasksGate = CompletableDeferred()
        vm.refresh()
        assertTrue(vm.state.value.refreshing)
        maintenance.tasksGate!!.complete(Unit)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun silentReloadDoesNotShowSpinner() {
        val vm = viewModel()
        maintenance.tasksGate = CompletableDeferred()
        vm.reloadSilently()
        assertFalse(vm.state.value.refreshing)
        maintenance.tasksGate!!.complete(Unit)
    }

    @Test fun concurrentRefreshIsIgnored() {
        maintenance.tasksGate = CompletableDeferred()
        val vm = viewModel()
        vm.refresh()
        vm.reloadSilently()
        assertEquals(1, maintenance.tasksCalls.size)
        maintenance.tasksGate!!.complete(Unit)
    }

    @Test fun refreshAfterDeletionShowsNotFound() {
        maintenance.tasksResult = Outcome.Success(listOf(task(1, TaskStatus.OK)))
        val vm = viewModel()
        vehicles.vehicleResult = Outcome.Failure(AppError.NotFound)
        vm.refresh()
        assertEquals(VehicleUiState(error = AppError.NotFound), vm.state.value)
    }

    @Test fun silentReloadAfterDeletionShowsNotFound() {
        maintenance.tasksResult = Outcome.Success(listOf(task(1, TaskStatus.OK)))
        val vm = viewModel()
        vehicles.vehicleResult = Outcome.Failure(AppError.NotFound)
        vm.reloadSilently()
        assertEquals(VehicleUiState(error = AppError.NotFound), vm.state.value)
    }

    @Test fun firstResumeOnlyMarksLaterResumesReloadSilently() {
        val vm = viewModel()
        vm.onResume()
        assertEquals(1, vehicles.vehicleCalls.size)
        maintenance.tasksResult = Outcome.Failure(AppError.Network)
        vm.onResume()
        assertEquals(2, vehicles.vehicleCalls.size)
        assertEquals(vehicle, vm.state.value.vehicle)
        assertNull(vm.state.value.error) // silent
    }

    @Test fun refreshDuringSilentReloadBecomesVisible() {
        val vm = viewModel()
        maintenance.tasksGate = CompletableDeferred()
        maintenance.tasksResult = Outcome.Failure(AppError.Network)
        vm.reloadSilently()
        vm.refresh()
        assertEquals(2, maintenance.tasksCalls.size)
        assertTrue(vm.state.value.refreshing)
        maintenance.tasksGate!!.complete(Unit)
        assertFalse(vm.state.value.refreshing)
        assertEquals(AppError.Network, vm.state.value.error)
    }

    private fun openDialog(): VehicleViewModel {
        val vm = viewModel()
        vm.openMileageDialog()
        return vm
    }

    @Test fun openPrefillsCurrentMileage() {
        val vm = openDialog()
        assertEquals(MileageDialogState("12345"), vm.state.value.mileageDialog)
    }

    @Test fun openWithoutVehicleDoesNothing() {
        vehicles.vehicleResult = Outcome.Failure(AppError.Network)
        val vm = viewModel()
        vm.openMileageDialog()
        assertNull(vm.state.value.mileageDialog)
    }

    @Test fun inputClearsError() {
        val vm = openDialog()
        vm.onMileageInput("abc")
        vm.saveMileage()
        assertEquals(MileageError.Invalid, vm.state.value.mileageDialog!!.error)
        vm.onMileageInput("12400")
        assertEquals(MileageDialogState("12400"), vm.state.value.mileageDialog)
    }

    @Test fun lowerMileageIsRejected() {
        val vm = openDialog()
        vm.onMileageInput("12000")
        vm.saveMileage()
        assertEquals(MileageError.TooLow(12345), vm.state.value.mileageDialog!!.error)
        assertTrue(vehicles.updateMileageCalls.isEmpty())
    }

    @Test fun invalidMileageIsRejected() {
        val vm = openDialog()
        vm.onMileageInput("12.600")
        vm.saveMileage()
        assertEquals(MileageError.Invalid, vm.state.value.mileageDialog!!.error)
        assertTrue(vehicles.updateMileageCalls.isEmpty())
    }

    @Test fun sameMileageClosesWithoutRequest() {
        val vm = openDialog()
        vm.saveMileage()
        assertNull(vm.state.value.mileageDialog)
        assertTrue(vehicles.updateMileageCalls.isEmpty())
    }

    @Test fun mileageSavedReloadsDetail() {
        vehicles.updateMileageResult = Outcome.Success(vehicle.copy(currentMileage = 12600))
        val vm = openDialog()
        vm.onMileageInput(" 12600 ")
        vehicles.vehicleResult = Outcome.Success(vehicle.copy(currentMileage = 12600))
        vm.saveMileage()
        assertEquals(listOf(vehicle.id to 12600), vehicles.updateMileageCalls)
        assertNull(vm.state.value.mileageDialog)
        assertEquals(2, vehicles.vehicleCalls.size)
        assertEquals(12600, vm.state.value.vehicle!!.currentMileage)
    }

    @Test fun mileageWithoutConnectionIsKeptOnTheDeviceAndShownAsPending() = kotlinx.coroutines.runBlocking {
        val outbox = de.bikemaintainer.app.data.storage.InMemoryOutbox()
        val pending = de.bikemaintainer.app.data.repository.PendingRepository(outbox)
        vehicles.vehicleResult = Outcome.Success(vehicle)
        vehicles.raiseQueued = true
        val vm = VehicleViewModel(7, vehicles, maintenance, measurements, auth, pending)
        vm.openMileageDialog()
        vm.onMileageInput("12600")
        vm.saveMileage()
        assertNull(vm.state.value.mileageDialog)
        assertTrue(vm.state.value.mileageQueued)
        vm.consumeMileageQueued()
        assertFalse(vm.state.value.mileageQueued)

        // What waits for this vehicle is listed; entries of other vehicles are not.
        outbox.add(de.bikemaintainer.app.data.storage.OutboxEntry("a", 7, 0, mileage = 12600))
        outbox.add(de.bikemaintainer.app.data.storage.OutboxEntry("b", 8, 0, mileage = 99))
        assertEquals(listOf("a"), vm.state.value.pending.map { it.id })
        // A waiting higher mileage counts as the current one.
        vm.openMileageDialog()
        assertEquals("12600", vm.state.value.mileageDialog!!.input)
        vm.onMileageInput("12500")
        vm.saveMileage()
        assertEquals(MileageError.TooLow(12600), vm.state.value.mileageDialog!!.error)

        vm.discardPending("a")
        assertTrue(vm.state.value.pending.isEmpty())
    }

    @Test fun saveFailureKeepsDialogWithError() {
        vehicles.updateMileageResult = Outcome.Failure(AppError.Network)
        val vm = openDialog()
        vm.onMileageInput("12600")
        vm.saveMileage()
        assertEquals(
            MileageDialogState("12600", MileageError.Save(AppError.Network), saving = false),
            vm.state.value.mileageDialog,
        )
    }

    @Test fun saveTwiceSendsOnce() {
        vehicles.updateMileageResult = Outcome.Success(vehicle)
        vehicles.updateMileageGate = CompletableDeferred()
        val vm = openDialog()
        vm.onMileageInput("12600")
        vm.saveMileage()
        assertTrue(vm.state.value.mileageDialog!!.saving)
        vm.saveMileage()
        assertEquals(1, vehicles.updateMileageCalls.size)
        vehicles.updateMileageGate!!.complete(Unit)
    }

    @Test fun dismissIgnoredWhileSaving() {
        vehicles.updateMileageResult = Outcome.Success(vehicle)
        vehicles.updateMileageGate = CompletableDeferred()
        val vm = openDialog()
        vm.onMileageInput("12600")
        vm.saveMileage()
        vm.dismissMileageDialog()
        assertTrue(vm.state.value.mileageDialog!!.saving)
        vehicles.updateMileageGate!!.complete(Unit)
        assertNull(vm.state.value.mileageDialog)
    }

    @Test fun dismissClosesDialog() {
        val vm = openDialog()
        vm.dismissMileageDialog()
        assertNull(vm.state.value.mileageDialog)
    }

    @Test fun dialogSurvivesSilentReload() {
        val vm = openDialog()
        vm.onMileageInput("123")
        vm.reloadSilently()
        assertEquals("123", vm.state.value.mileageDialog!!.input)
    }

    @Test fun terminalReloadErrorClosesOpenDialog() {
        val vm = openDialog()
        vehicles.vehicleResult = Outcome.Failure(AppError.NotFound)
        vm.reloadSilently()
        assertNull(vm.state.value.mileageDialog)
        assertEquals(AppError.NotFound, vm.state.value.error)
    }

    @Test fun mileageSavedDuringSilentReloadShowsTheNewMileage() {
        vehicles.updateMileageResult = Outcome.Success(vehicle.copy(currentMileage = 12600))
        val vm = openDialog()
        vm.onMileageInput("12600")
        // A resume reload is running and already has the old vehicle; the tasks are still loading.
        maintenance.tasksGate = CompletableDeferred()
        vm.reloadSilently()
        vehicles.vehicleResult = Outcome.Success(vehicle.copy(currentMileage = 12600))
        vm.saveMileage()
        maintenance.tasksGate!!.complete(Unit)
        assertEquals(3, vehicles.vehicleCalls.size)
        assertEquals(12600, vm.state.value.vehicle!!.currentMileage)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun vehicleDeletedBeforeMileageSaveShowsNotFound() {
        val vm = openDialog()
        vm.onMileageInput("12600")
        vehicles.updateMileageResult = Outcome.Failure(AppError.NotFound)
        vehicles.vehicleResult = Outcome.Failure(AppError.NotFound)
        vm.saveMileage()
        assertEquals(VehicleUiState(error = AppError.NotFound), vm.state.value)
    }
}
