package de.bikemaintainer.app.ui.template

import de.bikemaintainer.app.domain.AccessStatus
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.AppliedTask
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.TemplateApplication
import de.bikemaintainer.app.domain.TemplateApplyResult
import de.bikemaintainer.app.domain.TemplateSearchParams
import de.bikemaintainer.app.domain.TemplateSourceQuality
import de.bikemaintainer.app.domain.TemplateSummary
import de.bikemaintainer.app.domain.TemplateTask
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleTemplate
import de.bikemaintainer.app.domain.VehicleType
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.FakeMaintenanceRepository
import de.bikemaintainer.app.ui.FakeTemplateRepository
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

class TemplateViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val today = LocalDate.of(2026, 11, 26)
    private val vehicles = FakeVehicleRepository()
    private val maintenance = FakeMaintenanceRepository()
    private val templates = FakeTemplateRepository()
    private val auth = FakeAuthRepository()

    private val vehicle = Vehicle(7, "Hornet", VehicleType.MOTORCYCLE, "Honda", "CB 650 R", 2020, LocalDate.of(2020, 5, 1), 12345)

    private fun task(key: String, name: String = key) =
        TemplateTask(key, name, null, false, 6000, 12, null, null, emptyList(), emptyList())

    private fun summary(id: String, model: String = "CB 650 R") =
        TemplateSummary(id, VehicleType.MOTORCYCLE, "Honda", model, 2015, null, 2, TemplateSourceQuality.MANUFACTURER)

    private fun template(id: String = "honda-cb650r", vararg tasks: TemplateTask) = VehicleTemplate(
        id, VehicleType.MOTORCYCLE, "Honda", "CB 650 R", 2015, null, tasks.size, TemplateSourceQuality.MANUFACTURER,
        null, emptyList(), tasks.toList(),
    )

    private fun existingTask(templateKey: String?) = MaintenanceTask(
        1, 7, "Ölwechsel", false, true, TaskStatus.OK, null, null, null, null, null, null, templateKey = templateKey,
    )

    private fun viewModel(access: FakeAuthRepository = auth): TemplateViewModel {
        vehicles.vehicleResult = vehicles.vehicleResult ?: Outcome.Success(vehicle)
        return TemplateViewModel(7, vehicles, maintenance, templates, access) { today }
    }

    private fun oneHit(vararg tasks: TemplateTask) {
        templates.searchResult = Outcome.Success(listOf(summary("honda-cb650r")))
        templates.templates["honda-cb650r"] = template("honda-cb650r", *tasks)
    }

    @Test fun searchesWithTheVehicleDataAndPicksASingleHitAtOnce() {
        oneHit(task("oil"), task("chain"))
        val vm = viewModel()
        assertEquals(
            listOf(TemplateSearchParams("Honda", VehicleType.MOTORCYCLE, "CB 650 R", 2020)),
            templates.searchCalls,
        )
        assertEquals(listOf("honda-cb650r"), templates.getCalls)
        assertEquals(listOf("oil", "chain"), vm.state.value.rows.map { it.task.key })
        assertTrue(vm.state.value.rows.all { it.selected })
    }

    @Test fun severalHitsAreListedAndPickedByTheUser() {
        templates.searchResult = Outcome.Success(listOf(summary("a"), summary("b", "CB 650 F")))
        templates.templates["b"] = template("b", task("oil"))
        val vm = viewModel()
        assertEquals(2, vm.state.value.results!!.size)
        assertNull(vm.state.value.template)
        assertTrue(templates.getCalls.isEmpty())
        vm.pick(vm.state.value.results!![1])
        assertEquals("b", vm.state.value.template!!.id)
    }

    @Test fun noHitShowsAnEmptyResultAndAllowsAnotherSearch() {
        val vm = viewModel()
        assertEquals(emptyList<TemplateSummary>(), vm.state.value.results)
        vm.onMake("Yamaha")
        vm.onModel("")
        vm.search()
        assertEquals(TemplateSearchParams("Yamaha", VehicleType.MOTORCYCLE, "", 2020), templates.searchCalls.last())
    }

    @Test fun aVehicleWithoutMakeDoesNotSearchUntilAskedTo() {
        vehicles.vehicleResult = Outcome.Success(vehicle.copy(make = null, model = null))
        val vm = viewModel()
        assertTrue(templates.searchCalls.isEmpty())
        assertFalse(vm.state.value.canSearch)
        vm.onMake("H")
        assertFalse(vm.state.value.canSearch)
        vm.onMake(" Honda ")
        assertTrue(vm.state.value.canSearch)
        vm.search()
        assertEquals("Honda", templates.searchCalls.single().make)
    }

    @Test fun aFailedSearchIsShownAndCanBeRepeated() {
        templates.searchResult = Outcome.Failure(AppError.Network)
        val vm = viewModel()
        assertEquals(TemplateProblem.Search, vm.state.value.problem)
        assertNull(vm.state.value.results)
        templates.searchResult = Outcome.Success(emptyList())
        vm.search()
        assertNull(vm.state.value.problem)
        assertEquals(2, templates.searchCalls.size)
    }

    @Test fun aTemplateThatCannotBeLoadedIsShown() {
        templates.searchResult = Outcome.Success(listOf(summary("a")))
        templates.getFailure = AppError.Network
        val vm = viewModel()
        assertEquals(TemplateProblem.Load, vm.state.value.problem)
        assertNull(vm.state.value.template)
    }

    @Test fun failedLoadOfTheVehicleCanBeRetried() {
        vehicles.vehicleResult = Outcome.Failure(AppError.Network)
        val vm = viewModel()
        assertEquals(AppError.Network, vm.state.value.loadError)
        assertTrue(templates.searchCalls.isEmpty())
        vehicles.vehicleResult = Outcome.Success(vehicle)
        vm.retryLoad()
        assertNull(vm.state.value.loadError)
        assertEquals(1, templates.searchCalls.size)
    }

    @Test fun tasksThatCameFromTheTemplateAreMarkedAndNotSelected() {
        maintenance.tasksResult = Outcome.Success(listOf(existingTask("oil"), existingTask(null)))
        oneHit(task("oil"), task("chain"))
        val rows = viewModel().state.value.rows
        assertTrue(rows[0].existing)
        assertFalse(rows[0].selected)
        assertTrue(rows[1].selected)
    }

    @Test fun lastDoneIsOnlyOfferedForAVehicleThatHasBeenDriven() {
        oneHit(task("oil"))
        assertTrue(viewModel().state.value.showLastDone)
        vehicles.vehicleResult = Outcome.Success(vehicle.copy(currentMileage = 0))
        assertFalse(viewModel().state.value.showLastDone)
    }

    @Test fun fillEmptyFieldsTakesTheMileageAndToday() {
        oneHit(task("oil"), task("chain"))
        val vm = viewModel()
        vm.onLastDoneKm(0, "9.000 km")
        vm.fillEmptyFields()
        val rows = vm.state.value.rows
        assertEquals(9000, rows[0].lastDoneKm)
        assertEquals(12345, rows[1].lastDoneKm)
        assertEquals(today, rows[1].lastDoneDate)
        vm.onLastDoneKm(1, "")
        vm.onLastDoneDate(1, null)
        assertNull(vm.state.value.rows[1].lastDoneKm)
        assertNull(vm.state.value.rows[1].lastDoneDate)
    }

    @Test fun applyingNeedsTheDisclaimerToBeConfirmed() {
        oneHit(task("oil"))
        val vm = viewModel()
        assertFalse(vm.state.value.confirmed)
        assertFalse(vm.state.value.canApply)
        vm.apply()
        assertTrue(templates.applyCalls.isEmpty())
        vm.onConfirmed(true)
        assertTrue(vm.state.value.canApply)
        vm.onConfirmed(false)
        assertFalse(vm.state.value.canApply)
    }

    @Test fun theConfirmationStartsOverEachTimeTheApplyStepOpens() {
        oneHit(task("oil"))
        val vm = viewModel()
        vm.onConfirmed(true)
        vm.backToSearch()
        assertFalse(vm.state.value.confirmed)
        vm.pick(summary("honda-cb650r"))
        assertFalse(vm.state.value.confirmed)
        assertFalse(viewModel().state.value.confirmed)
    }

    @Test fun nothingSelectedCannotBeApplied() {
        oneHit(task("oil"))
        val vm = viewModel()
        vm.toggle(0)
        vm.onConfirmed(true)
        assertEquals(0, vm.state.value.selectedCount)
        assertFalse(vm.state.value.canApply)
    }

    @Test fun applySendsTheSelectedTasksWithTheirLastDoneValues() {
        oneHit(task("oil"), task("chain"), task("brakes"))
        templates.applyResult = Outcome.Success(TemplateApplyResult(2, 0))
        val vm = viewModel()
        vm.toggle(2)
        vm.onLastDoneKm(0, "0")
        vm.onLastDoneDate(1, LocalDate.of(2026, 1, 1))
        vm.onConfirmed(true)
        vm.apply()
        assertEquals(
            listOf(
                7L to TemplateApplication(
                    "honda-cb650r",
                    listOf(AppliedTask("oil", 0, null), AppliedTask("chain", null, LocalDate.of(2026, 1, 1))),
                ),
            ),
            templates.applyCalls,
        )
        assertEquals(TemplateApplyResult(2, 0), vm.state.value.applied)
    }

    @Test fun aSecondApplyWhileOneRunsIsIgnored() {
        oneHit(task("oil"))
        templates.applyGate = CompletableDeferred()
        val vm = viewModel()
        vm.onConfirmed(true)
        vm.apply()
        vm.apply()
        assertTrue(vm.state.value.applying)
        assertEquals(1, templates.applyCalls.size)
        vm.onConfirmed(false)
        vm.toggle(0)
        vm.backToSearch()
        assertTrue(vm.state.value.confirmed)
        assertTrue(vm.state.value.rows[0].selected)
        assertEquals("honda-cb650r", vm.state.value.template!!.id)
        templates.applyGate!!.complete(Unit)
        assertEquals(TemplateApplyResult(0, 0), vm.state.value.applied)
        vm.apply()
        assertEquals(1, templates.applyCalls.size)
    }

    @Test fun aFailedApplyShowsTheErrorAndNothingIsQueued() {
        oneHit(task("oil"))
        templates.applyResult = Outcome.Failure(AppError.Network)
        val vm = viewModel()
        vm.onConfirmed(true)
        vm.apply()
        assertEquals(TemplateProblem.Apply(AppError.Network), vm.state.value.problem)
        assertFalse(vm.state.value.applying)
        assertNull(vm.state.value.applied)
        // The user can try again.
        templates.applyResult = Outcome.Success(TemplateApplyResult(1, 0))
        vm.apply()
        assertEquals(TemplateApplyResult(1, 0), vm.state.value.applied)
    }

    @Test fun aLastDoneMileageAboveTheVehiclesCannotBeApplied() {
        oneHit(task("oil"), task("chain"))
        val vm = viewModel()
        vm.onConfirmed(true)
        vm.onLastDoneKm(0, "12346")
        assertEquals(LastDoneError.KM_TOO_HIGH, vm.state.value.lastDoneError(vm.state.value.rows[0]))
        assertFalse(vm.state.value.canApply)
        vm.toggle(0)
        assertTrue(vm.state.value.canApply)
        vm.toggle(0)
        vm.onLastDoneKm(0, "12345")
        assertNull(vm.state.value.lastDoneError(vm.state.value.rows[0]))
        assertTrue(vm.state.value.canApply)
    }

    @Test fun aLastDoneDateOutsideRegistrationAndTodayCannotBeApplied() {
        oneHit(task("oil"))
        val vm = viewModel()
        vm.onConfirmed(true)
        for (date in listOf(today.plusDays(1), LocalDate.of(2020, 4, 30))) {
            vm.onLastDoneDate(0, date)
            assertEquals(LastDoneError.DATE_OUT_OF_RANGE, vm.state.value.lastDoneError(vm.state.value.rows[0]))
            assertFalse(vm.state.value.canApply)
        }
        for (date in listOf(today, LocalDate.of(2020, 5, 1))) {
            vm.onLastDoneDate(0, date)
            assertNull(vm.state.value.lastDoneError(vm.state.value.rows[0]))
            assertTrue(vm.state.value.canApply)
        }
    }

    @Test fun aRejectedApplyIsKeptAsHttp400ForItsOwnMessage() {
        oneHit(task("oil"))
        templates.applyResult = Outcome.Failure(AppError.Http(400, "VALIDATION_FAILED"))
        val vm = viewModel()
        vm.onConfirmed(true)
        vm.apply()
        assertEquals(TemplateProblem.Apply(AppError.Http(400, "VALIDATION_FAILED")), vm.state.value.problem)
        assertFalse(vm.state.value.applying)
    }

    @Test fun aRefusedApplyMakesTheAccountReadOnly() {
        oneHit(task("oil"))
        templates.applyResult = Outcome.Failure(AppError.Http(403, "SUBSCRIPTION_REQUIRED"))
        val vm = viewModel()
        vm.onConfirmed(true)
        vm.apply()
        assertEquals(AccessStatus.EXPIRED, auth.access.value.status)
        assertTrue(vm.state.value.problem is TemplateProblem.Apply)
    }

    @Test fun anIdOutOfRangeIsIgnored() {
        oneHit(task("oil"))
        val vm = viewModel()
        val before = vm.state.value.rows
        vm.toggle(5)
        vm.onLastDoneKm(5, "1")
        assertEquals(before, vm.state.value.rows)
    }

    @Test fun anExistingTaskCannotBeSelected() {
        maintenance.tasksResult = Outcome.Success(listOf(existingTask("oil")))
        oneHit(task("oil"))
        val vm = viewModel()
        vm.toggle(0)
        assertFalse(vm.state.value.rows[0].selected)
    }
}
