package de.bikemaintainer.app.ui.logmaintenance

import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleType
import de.bikemaintainer.app.ui.FakeMaintenanceRepository
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
    private val today = LocalDate.of(2026, 11, 9)
    private val vehicle = Vehicle(3, "Africa Twin", VehicleType.MOTORCYCLE, null, null, 2020, LocalDate.of(2020, 5, 1), 24500)

    private fun task(id: Long, status: TaskStatus, active: Boolean = true) =
        MaintenanceTask(id, 3, "T$id", false, active, status, null, null, null, null, null, null)

    private fun viewModel(): LogMaintenanceViewModel {
        if (vehicles.vehicleResult == null) vehicles.vehicleResult = Outcome.Success(vehicle)
        if (vehicles.updateMileageResult == null) vehicles.updateMileageResult = Outcome.Success(vehicle)
        maintenance.tasksResult = Outcome.Success(
            listOf(
                task(1, TaskStatus.OVERDUE),
                task(2, TaskStatus.DUE_SOON),
                task(3, TaskStatus.OK),
                task(4, TaskStatus.OVERDUE, active = false),
            ),
        )
        return LogMaintenanceViewModel(3, vehicles, maintenance) { today }
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

    @Test fun higherMileageRaisesVehicle() {
        val vm = viewModel()
        vm.onMileageInput("24800")
        assertTrue(vm.state.value.offerRaise)
        vm.save()
        assertEquals(listOf(vehicle to 24800), vehicles.updateMileageCalls)
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
}
