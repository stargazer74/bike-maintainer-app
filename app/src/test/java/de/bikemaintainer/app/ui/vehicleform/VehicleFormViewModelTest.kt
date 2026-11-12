package de.bikemaintainer.app.ui.vehicleform

import de.bikemaintainer.app.domain.AccessStatus
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleInput
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

class VehicleFormViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val vehicles = FakeVehicleRepository()
    private val auth = FakeAuthRepository()
    private val today = LocalDate.of(2026, 11, 12)
    private val vehicle = Vehicle(7, "Golf", VehicleType.CAR, "VW", null, 2015, LocalDate.of(2015, 3, 1), 99000)

    private fun viewModel(id: Long? = null) = VehicleFormViewModel(id, vehicles, auth) { today }

    @Test fun newVehicleStartsEmptyAndShowsProblemsOnlyAfterSaving() {
        val vm = viewModel()
        assertFalse(vm.state.value.editing)
        assertEquals("2026", vm.state.value.draft!!.modelYear)
        assertFalse(vm.state.value.showErrors)
        vm.save()
        assertTrue(vm.state.value.showErrors)
        assertTrue(vehicles.createVehicleCalls.isEmpty())
    }

    @Test fun savingANewVehicleReturnsItsId() {
        vehicles.createVehicleResult = Outcome.Success(vehicle)
        val vm = viewModel()
        vm.edit { it.copy(name = "Golf", type = VehicleType.CAR, currentMileage = "99000") }
        vm.save()
        assertEquals(
            VehicleInput("Golf", VehicleType.CAR, null, null, 2026, LocalDate.of(2026, 1, 1), 99000),
            vehicles.createVehicleCalls.single(),
        )
        assertEquals(7L, vm.state.value.saved)
    }

    @Test fun editingLoadsAndUpdatesTheVehicle() {
        vehicles.vehicleResult = Outcome.Success(vehicle)
        vehicles.updateVehicleResult = Outcome.Success(vehicle)
        val vm = viewModel(7)
        assertTrue(vm.state.value.editing)
        assertEquals("VW", vm.state.value.draft!!.make)
        vm.edit { it.copy(currentMileage = "100000") }
        vm.save()
        assertEquals(7L, vehicles.updateVehicleCalls.single().first)
        assertEquals(100000, vehicles.updateVehicleCalls.single().second.currentMileage)
    }

    @Test fun failedLoadCanBeRetried() {
        vehicles.vehicleResult = Outcome.Failure(AppError.Network)
        val vm = viewModel(7)
        assertNull(vm.state.value.draft)
        assertEquals(AppError.Network, vm.state.value.loadError)
        vehicles.vehicleResult = Outcome.Success(vehicle)
        vm.retryLoad()
        assertEquals("Golf", vm.state.value.draft!!.name)
        assertNull(vm.state.value.loadError)
    }

    @Test fun refusedSaveMakesTheAccountReadOnly() {
        vehicles.createVehicleResult = Outcome.Failure(AppError.Http(403, "SUBSCRIPTION_REQUIRED"))
        val vm = viewModel()
        vm.edit { it.copy(name = "Golf") }
        vm.save()
        assertEquals(AccessStatus.EXPIRED, auth.access.value.status)
        assertEquals(AppError.Http(403, "SUBSCRIPTION_REQUIRED"), vm.state.value.saveError)
        assertNull(vm.state.value.saved)
    }

    @Test fun noSecondSaveAndNoEditsWhileSaving() {
        vehicles.createVehicleResult = Outcome.Success(vehicle)
        vehicles.createVehicleGate = CompletableDeferred()
        val vm = viewModel()
        vm.edit { it.copy(name = "Golf") }
        vm.save()
        vm.save()
        vm.edit { it.copy(name = "Polo") }
        assertEquals(1, vehicles.createVehicleCalls.size)
        assertEquals("Golf", vm.state.value.draft!!.name)
        vehicles.createVehicleGate!!.complete(Unit)
        assertEquals(7L, vm.state.value.saved)
    }
}
