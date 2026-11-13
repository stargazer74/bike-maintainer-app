package de.bikemaintainer.app.ui.logmaintenance

import androidx.lifecycle.SavedStateHandle
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleType
import de.bikemaintainer.app.ui.FakeMaintenanceRepository
import de.bikemaintainer.app.ui.FakeMeasurementRepository
import de.bikemaintainer.app.ui.FakeMaintenanceRepository.LogCall
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

class LogMaintenanceViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val vehicles = FakeVehicleRepository()
    private val maintenance = FakeMaintenanceRepository()
    private val measurements = FakeMeasurementRepository()
    private val today = LocalDate.of(2026, 11, 9)
    private val vehicle = Vehicle(3, "Africa Twin", VehicleType.MOTORCYCLE, null, null, 2020, LocalDate.of(2020, 5, 1), 24500)

    private fun task(id: Long, status: TaskStatus, active: Boolean = true) =
        MaintenanceTask(id, 3, "T$id", false, active, status, null, null, null, null, null, null)

    private val allTasks = listOf(
        task(1, TaskStatus.OVERDUE),
        task(2, TaskStatus.DUE_SOON),
        task(3, TaskStatus.OK),
        task(4, TaskStatus.OVERDUE, active = false),
    )

    private fun viewModel(
        savedState: SavedStateHandle = SavedStateHandle(),
        tasks: List<MaintenanceTask> = allTasks,
    ): LogMaintenanceViewModel {
        if (vehicles.vehicleResult == null) vehicles.vehicleResult = Outcome.Success(vehicle)
        if (vehicles.updateMileageResult == null) vehicles.updateMileageResult = Outcome.Success(vehicle)
        maintenance.tasksResult = Outcome.Success(tasks)
        return LogMaintenanceViewModel(
            3, vehicles, maintenance, savedState, measurements,
            today = { today }, shimDebounceMillis = 0, decimalSeparator = ',',
        )
    }

    @Test fun prefillsTodayMileageAndDueTasks() {
        val state = viewModel().state.value
        assertEquals(setOf(1L, 2L), state.selected)
        assertFalse(4L in state.tasks.map { it.id })
        assertEquals(3, state.tasks.size)
        assertEquals("24500", state.mileageInput)
        assertEquals(today, state.date)
        assertEquals(vehicle, state.vehicle)
    }

    @Test fun notesAreCappedAt1000() {
        val vm = viewModel()
        vm.onNotes("x".repeat(1200))
        assertEquals(1000, vm.state.value.notes.length)
    }

    @Test fun noTaskSelectedIsRejected() {
        val vm = viewModel()
        vm.toggleTask(1)
        vm.toggleTask(2)
        vm.save()
        assertEquals(LogError.NoTask, vm.state.value.error)
        assertTrue(maintenance.logCalls.isEmpty())
    }

    @Test fun invalidMileageIsRejected() {
        val vm = viewModel()
        vm.onMileageInput("abc")
        vm.save()
        assertEquals(LogError.InvalidMileage, vm.state.value.error)
        assertTrue(maintenance.logCalls.isEmpty())
    }

    @Test fun editingClearsError() {
        val vm = viewModel()
        vm.onMileageInput("abc")
        vm.save()
        vm.onMileageInput("100")
        assertNull(vm.state.value.error)
    }

    @Test fun savesLogWithSelectionAndTrimmedNotes() {
        val vm = viewModel()
        vm.onNotes("  ")
        vm.save()
        assertEquals(listOf(LogCall(3, today, 24500, setOf(1L, 2L), null)), maintenance.logCalls)
        assertEquals(LogResult.SAVED, vm.state.value.done)
        assertTrue(vehicles.updateMileageCalls.isEmpty())
    }

    @Test fun withoutConnectionTheLogIsQueuedWithTheRaiseAndNotRaisedNow() {
        maintenance.logQueued = true
        val vm = viewModel()
        vm.onMileageInput("24800")
        vm.save()
        assertEquals(LogResult.QUEUED, vm.state.value.done)
        assertEquals(listOf<Int?>(24800), maintenance.logRaises)
        // The queued log raises the mileage itself once it is sent.
        assertTrue(vehicles.updateMileageCalls.isEmpty())
    }

    @Test fun everyAttemptOfTheSameFormSendsTheSameId() {
        maintenance.logResult = Outcome.Failure(AppError.Http(503, null))
        val saved = SavedStateHandle()
        val vm = viewModel(saved)
        vm.save()
        vm.save()
        // Also after the process was gone: the id is kept with the form.
        viewModel(saved).save()
        assertEquals(3, maintenance.logEntryIds.size)
        assertEquals(1, maintenance.logEntryIds.toSet().size)
    }

    @Test fun higherMileageRaisesVehicle() {
        val vm = viewModel()
        vm.onMileageInput("24800")
        assertTrue(vm.state.value.offerRaise)
        vm.save()
        assertEquals(listOf(vehicle.id to 24800), vehicles.updateMileageCalls)
        assertEquals(LogResult.SAVED, vm.state.value.done)
    }

    @Test fun raiseUncheckedDoesNotUpdate() {
        val vm = viewModel()
        vm.onMileageInput("24800")
        vm.onRaiseMileage(false)
        vm.save()
        assertTrue(vehicles.updateMileageCalls.isEmpty())
        assertEquals(LogResult.SAVED, vm.state.value.done)
    }

    @Test fun lowerMileageIsAllowedWithoutRaise() {
        val vm = viewModel()
        vm.onMileageInput("20000")
        assertFalse(vm.state.value.offerRaise)
        vm.save()
        assertEquals(20000, maintenance.logCalls.single().mileage)
        assertTrue(vehicles.updateMileageCalls.isEmpty())
    }

    @Test fun raiseFailureStillFinishesAsSaved() {
        val vm = viewModel()
        vehicles.updateMileageResult = Outcome.Failure(AppError.Network)
        vm.onMileageInput("24800")
        vm.save()
        assertEquals(LogResult.SAVED_MILEAGE_FAILED, vm.state.value.done)
        assertEquals(1, maintenance.logCalls.size)
    }

    @Test fun saveFailureKeepsInput() {
        val vm = viewModel()
        maintenance.logResult = Outcome.Failure(AppError.Http(400, null))
        vm.onNotes("hello")
        vm.save()
        val state = vm.state.value
        assertEquals(LogError.Save(AppError.Http(400, null)), state.error)
        assertNull(state.done)
        assertFalse(state.saving)
        assertEquals("hello", state.notes)
        assertEquals(setOf(1L, 2L), state.selected)
        assertEquals("24500", state.mileageInput)
        assertTrue(vehicles.updateMileageCalls.isEmpty())
    }

    @Test fun saveTwiceSendsOnce() {
        val vm = viewModel()
        maintenance.logGate = CompletableDeferred()
        vm.save()
        vm.save()
        assertTrue(vm.state.value.saving)
        assertEquals(1, maintenance.logCalls.size)
        maintenance.logGate!!.complete(Unit)
        assertEquals(1, maintenance.logCalls.size)
        assertEquals(LogResult.SAVED, vm.state.value.done)
    }

    @Test fun deletedVehicleShowsNotFound() {
        vehicles.vehicleResult = Outcome.Failure(AppError.NotFound)
        val state = viewModel().state.value
        assertTrue(state.loadError!!.isNotFound())
        assertNull(state.vehicle)
    }

    @Test fun retryLoadsAgainAndKeepsFormAfterwards() {
        vehicles.vehicleResult = Outcome.Failure(AppError.Network)
        val vm = viewModel()
        assertEquals(AppError.Network, vm.state.value.loadError)
        vehicles.vehicleResult = Outcome.Success(vehicle)
        vm.retryLoad()
        assertNull(vm.state.value.loadError)
        assertEquals("24500", vm.state.value.mileageInput)
    }

    @Test fun savingBeforeLoadDoesNothing() {
        vehicles.vehicleResult = Outcome.Failure(AppError.Network)
        val vm = viewModel()
        vm.save()
        assertTrue(maintenance.logCalls.isEmpty())
    }

    @Test fun consumeDoneClearsResult() {
        maintenance.logResult = Outcome.Success(Unit)
        val vm = viewModel()
        vm.save()
        assertEquals(LogResult.SAVED, vm.state.value.done)
        vm.consumeDone()
        assertNull(vm.state.value.done)
    }

    @Test fun formStaysLockedAfterSuccess() {
        val vm = viewModel()
        vm.save()
        assertEquals(LogResult.SAVED, vm.state.value.done)
        vm.consumeDone()
        // The screen is still composed during the pop animation: a second tap must not send a second log.
        vm.save()
        assertEquals(1, maintenance.logCalls.size)
        assertTrue(vm.state.value.finished)
        vm.onNotes("late")
        vm.toggleTask(1)
        vm.onMileageInput("1")
        assertEquals("", vm.state.value.notes)
        assertEquals(setOf(1L, 2L), vm.state.value.selected)
        assertEquals("24500", vm.state.value.mileageInput)
    }

    @Test fun formSurvivesProcessDeath() {
        val handle = SavedStateHandle()
        val first = viewModel(handle)
        first.onNotes("Neues Öl")
        first.toggleTask(2)
        first.toggleTask(3)
        first.onDate(today.minusDays(3))
        first.onMileageInput("24800")
        first.onRaiseMileage(false)
        // A new ViewModel with the same handle stands for the restored process: the saved form wins over the prefill.
        val state = viewModel(handle).state.value
        assertEquals("Neues Öl", state.notes)
        assertEquals(setOf(1L, 3L), state.selected)
        assertEquals(today.minusDays(3), state.date)
        assertEquals("24800", state.mileageInput)
        assertFalse(state.raiseMileage)
        assertEquals(vehicle, state.vehicle)
    }

    @Test fun restoredSelectionDropsTasksThatNoLongerExist() {
        val handle = SavedStateHandle()
        viewModel(handle).toggleTask(3)
        val state = viewModel(handle, tasks = listOf(task(1, TaskStatus.OVERDUE), task(2, TaskStatus.DUE_SOON))).state.value
        assertEquals(setOf(1L, 2L), state.selected)
    }

    @Test fun untouchedFormIsPrefilledFromServerAfterRestore() {
        val handle = SavedStateHandle()
        viewModel(handle)
        vehicles.vehicleResult = Outcome.Success(vehicle.copy(currentMileage = 24600))
        assertEquals("24500", viewModel(handle).state.value.mileageInput) // saved at the first prefill
        assertEquals("24600", viewModel().state.value.mileageInput)
    }

    @Test fun restoredAfterSuccessStaysFinished() {
        val handle = SavedStateHandle()
        viewModel(handle).save()
        val vm = viewModel(handle)
        assertEquals(LogResult.SAVED, vm.state.value.done)
        vm.save()
        assertEquals(1, maintenance.logCalls.size)
    }

    @Test fun notesCapDoesNotSplitAnEmoji() {
        val vm = viewModel()
        vm.onNotes("x".repeat(999) + "\uD83D\uDE00" + "y")
        assertEquals("x".repeat(999), vm.state.value.notes)
        vm.onNotes("x".repeat(998) + "\uD83D\uDE00" + "y")
        assertEquals("x".repeat(998) + "\uD83D\uDE00", vm.state.value.notes)
    }

    @Test fun rejectedInputReloadsTasksAndKeepsTheForm() {
        val vm = viewModel()
        maintenance.logResult = Outcome.Failure(AppError.Http(400, null))
        vm.onNotes("hello")
        vm.onMileageInput("24800")
        vm.onDate(today.minusDays(1))
        vm.toggleTask(3)
        // Task 2 was deleted in the web app meanwhile, task 5 is new.
        maintenance.tasksResult = Outcome.Success(listOf(task(1, TaskStatus.OVERDUE), task(3, TaskStatus.OK), task(5, TaskStatus.OVERDUE)))
        vehicles.vehicleResult = Outcome.Success(vehicle.copy(currentMileage = 24700))
        vm.save()
        val state = vm.state.value
        assertEquals(2, maintenance.tasksCalls.size)
        assertEquals(listOf(1L, 5L, 3L), state.tasks.map { it.id })
        assertEquals(setOf(1L, 3L), state.selected)
        assertEquals("hello", state.notes)
        assertEquals("24800", state.mileageInput)
        assertEquals(today.minusDays(1), state.date)
        assertEquals(24700, state.vehicle!!.currentMileage)
        assertEquals(LogError.Save(AppError.Http(400, null)), state.error)
        assertFalse(state.saving)
    }

    @Test fun vehicleDeletedBeforeSaveShowsNotFound() {
        val vm = viewModel()
        maintenance.logResult = Outcome.Failure(AppError.NotFound)
        vm.save()
        val state = vm.state.value
        assertEquals(AppError.NotFound, state.loadError)
        assertNull(state.vehicle)
        assertFalse(state.saving)
        vm.retryLoad()
        assertEquals(1, vehicles.vehicleCalls.size) // terminal: only "Back" is offered
    }
}
