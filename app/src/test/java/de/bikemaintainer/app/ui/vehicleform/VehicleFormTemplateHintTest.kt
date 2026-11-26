package de.bikemaintainer.app.ui.vehicleform

import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TemplateSearchParams
import de.bikemaintainer.app.domain.TemplateSourceQuality
import de.bikemaintainer.app.domain.TemplateSummary
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleType
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.FakeTemplateRepository
import de.bikemaintainer.app.ui.FakeVehicleRepository
import de.bikemaintainer.app.ui.MainDispatcherRule
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The hint "a maintenance plan exists for this model" in the form of a new vehicle (Epic backend#150). */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleFormTemplateHintTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule val mainRule = MainDispatcherRule(dispatcher)

    private val vehicles = FakeVehicleRepository()
    private val templates = FakeTemplateRepository()
    private val today = LocalDate.of(2026, 11, 26)
    private val created = Vehicle(9, "Hornet", VehicleType.MOTORCYCLE, "Honda", "CB 650 R", 2026, today, 0)
    private val hit = TemplateSummary("t", VehicleType.MOTORCYCLE, "Honda", "CB 650 R", 2015, null, 2, TemplateSourceQuality.MANUFACTURER)

    private fun viewModel(id: Long? = null) = VehicleFormViewModel(id, vehicles, FakeAuthRepository(), templates) { today }

    private fun settle() = dispatcher.scheduler.advanceTimeBy(500)

    @Test fun makeAndModelWithAHitShowTheHint() {
        templates.searchResult = Outcome.Success(listOf(hit))
        val vm = viewModel()
        vm.edit { it.copy(make = "Honda", model = "CB 650 R", modelYear = "2020") }
        assertFalse(vm.state.value.templateHint)
        settle()
        assertTrue(vm.state.value.templateHint)
        assertEquals(
            listOf(TemplateSearchParams("Honda", VehicleType.MOTORCYCLE, "CB 650 R", 2020)),
            templates.searchCalls,
        )
    }

    @Test fun aHalfFilledFormSearchesNothingAndShowsNoHint() {
        val vm = viewModel()
        vm.edit { it.copy(make = "K") }
        settle()
        vm.edit { it.copy(make = "Ka") }
        settle()
        assertTrue(templates.searchCalls.isEmpty())
        assertFalse(vm.state.value.templateHint)
    }

    @Test fun typingIsDebouncedToASingleSearch() {
        templates.searchResult = Outcome.Success(listOf(hit))
        val vm = viewModel()
        vm.edit { it.copy(make = "Honda", model = "C") }
        dispatcher.scheduler.advanceTimeBy(200)
        vm.edit { it.copy(model = "CB") }
        dispatcher.scheduler.advanceTimeBy(200)
        vm.edit { it.copy(model = "CB 650 R") }
        settle()
        assertEquals(listOf("CB 650 R"), templates.searchCalls.map { it.model })
    }

    @Test fun unchangedInputDoesNotSearchAgain() {
        val vm = viewModel()
        vm.edit { it.copy(make = "Honda", model = "CB") }
        settle()
        vm.edit { it.copy(name = "Hornet", make = "Honda", model = "CB") }
        settle()
        assertEquals(1, templates.searchCalls.size)
    }

    @Test fun anEmptiedFieldRemovesTheHint() {
        templates.searchResult = Outcome.Success(listOf(hit))
        val vm = viewModel()
        vm.edit { it.copy(make = "Honda", model = "CB") }
        settle()
        assertTrue(vm.state.value.templateHint)
        vm.edit { it.copy(model = "") }
        assertFalse(vm.state.value.templateHint)
    }

    @Test fun aFailingSearchShowsNoHintAndSavingStillWorks() {
        templates.searchResult = Outcome.Failure(AppError.Http(500, null))
        vehicles.createVehicleResult = Outcome.Success(created)
        val vm = viewModel()
        vm.edit { it.copy(name = "Hornet", make = "Honda", model = "CB 650 R", modelYear = "2020") }
        settle()
        assertFalse(vm.state.value.templateHint)
        vm.save()
        assertEquals(9L, vm.state.value.saved)
        assertFalse(vm.state.value.offerTemplate)
    }

    @Test fun savingWithAHintOffersTheTemplate() {
        templates.searchResult = Outcome.Success(listOf(hit))
        vehicles.createVehicleResult = Outcome.Success(created)
        val vm = viewModel()
        vm.edit { it.copy(name = "Hornet", make = "Honda", model = "CB 650 R", modelYear = "2020") }
        settle()
        vm.save()
        assertEquals(9L, vm.state.value.saved)
        assertTrue(vm.state.value.offerTemplate)
    }

    @Test fun editingAVehicleNeverSearches() {
        vehicles.vehicleResult = Outcome.Success(created)
        vehicles.updateVehicleResult = Outcome.Success(created)
        val vm = viewModel(9)
        vm.edit { it.copy(make = "Honda", model = "CB 650 R") }
        settle()
        vm.save()
        assertTrue(templates.searchCalls.isEmpty())
        assertFalse(vm.state.value.templateHint)
        assertFalse(vm.state.value.offerTemplate)
    }
}
