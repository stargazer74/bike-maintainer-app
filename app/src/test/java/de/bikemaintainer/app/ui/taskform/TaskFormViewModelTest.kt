package de.bikemaintainer.app.ui.taskform

import de.bikemaintainer.app.domain.AccessStatus
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskInput
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.FakeMaintenanceRepository
import de.bikemaintainer.app.ui.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TaskFormViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val maintenance = FakeMaintenanceRepository()
    private val auth = FakeAuthRepository()
    private val task = MaintenanceTask(
        5, 7, "Ventile", false, true, TaskStatus.OK, null, null, null, null, null, null,
        intervalKm = 24000,
    )

    @Test fun newTaskIsCreatedForTheVehicle() {
        maintenance.createTaskResult = Outcome.Success(task)
        val vm = TaskFormViewModel(7, null, maintenance, auth)
        assertFalse(vm.state.value.editing)
        vm.edit { it.copy(name = "Ölwechsel", intervalKm = "6000", intervalMonths = "12") }
        vm.save()
        assertEquals(7L to TaskInput("Ölwechsel", null, 6000, 12, null, null, oneTime = false, active = true), maintenance.createTaskCalls.single())
        assertTrue(vm.state.value.saved)
    }

    @Test fun invalidInputIsNotSent() {
        val vm = TaskFormViewModel(7, null, maintenance, auth)
        vm.edit { it.copy(name = "Ölwechsel") }
        vm.save()
        assertTrue(vm.state.value.showErrors)
        assertTrue(maintenance.createTaskCalls.isEmpty())
    }

    @Test fun editingCanSwitchTheTaskOff() {
        maintenance.taskResult = Outcome.Success(task)
        maintenance.updateTaskResult = Outcome.Success(task.copy(active = false))
        val vm = TaskFormViewModel(7, 5, maintenance, auth)
        assertEquals("24000", vm.state.value.draft!!.intervalKm)
        vm.edit { it.copy(active = false) }
        vm.save()
        val (vehicleId, taskId, input) = maintenance.updateTaskCalls.single()
        assertEquals(7L, vehicleId)
        assertEquals(5L, taskId)
        assertFalse(input.active)
        assertEquals(24000, input.intervalKm)
    }

    @Test fun deletedTaskShowsNotFound() {
        maintenance.taskResult = Outcome.Failure(AppError.NotFound)
        val vm = TaskFormViewModel(7, 5, maintenance, auth)
        assertNull(vm.state.value.draft)
        assertEquals(AppError.NotFound, vm.state.value.loadError)
    }

    @Test fun refusedSaveMakesTheAccountReadOnly() {
        maintenance.createTaskResult = Outcome.Failure(AppError.Http(403, "SUBSCRIPTION_REQUIRED"))
        val vm = TaskFormViewModel(7, null, maintenance, auth)
        vm.edit { it.copy(name = "Kette", intervalKm = "1000") }
        vm.save()
        assertEquals(AccessStatus.EXPIRED, auth.access.value.status)
        assertFalse(vm.state.value.saved)
    }
}
