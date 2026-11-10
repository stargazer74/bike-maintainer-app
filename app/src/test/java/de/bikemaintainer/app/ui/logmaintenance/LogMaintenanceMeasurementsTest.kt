package de.bikemaintainer.app.ui.logmaintenance

import androidx.lifecycle.SavedStateHandle
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.LatestMeasurements
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.MeasurementContext
import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.Measurements
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.ShimInput
import de.bikemaintainer.app.domain.ShimSuggestion
import de.bikemaintainer.app.domain.Specification
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.ValveAdjustment
import de.bikemaintainer.app.domain.ValveKind
import de.bikemaintainer.app.domain.ValveMeasurement
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleType
import de.bikemaintainer.app.ui.FakeMaintenanceRepository
import de.bikemaintainer.app.ui.FakeMeasurementRepository
import de.bikemaintainer.app.ui.FakeVehicleRepository
import de.bikemaintainer.app.ui.MainDispatcherRule
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogMaintenanceMeasurementsTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule val mainRule = MainDispatcherRule(dispatcher)

    private val vehicles = FakeVehicleRepository()
    private val maintenance = FakeMaintenanceRepository()
    private val measurements = FakeMeasurementRepository()
    private val today = LocalDate.of(2026, 11, 9)
    private val vehicle = Vehicle(3, "Africa Twin", VehicleType.MOTORCYCLE, null, null, 2020, LocalDate.of(2020, 5, 1), 24500)

    private val spec = Specification(
        cylinders = 1,
        valvesPerCylinder = 2,
        valveAdjustment = ValveAdjustment.SHIM,
        intakeClearanceMin = BigDecimal("0.10"),
        intakeClearanceMax = BigDecimal("0.15"),
        exhaustClearanceMin = BigDecimal("0.20"),
        exhaustClearanceMax = BigDecimal("0.25"),
        shimStep = BigDecimal("0.05"),
        chainWearLinks = 20,
    )
    private val latest = LatestMeasurements(
        Measurements(valves = listOf(ValveMeasurement(1, ValveKind.INTAKE, 1, null, BigDecimal("2.55"), BigDecimal("2.50")))),
    )
    private val suggestion = ShimSuggestion(BigDecimal("2.450"), BigDecimal("0.13"), null)

    private fun context(specification: Specification = spec) = Outcome.Success(
        MeasurementContext(MeasurementTemplate.entries.toList(), specification, latest),
    )

    private fun viewModel(
        savedState: SavedStateHandle = SavedStateHandle(),
        debounce: Long = 0,
    ): LogMaintenanceViewModel {
        vehicles.vehicleResult = Outcome.Success(vehicle)
        maintenance.tasksResult = Outcome.Success(
            listOf(MaintenanceTask(1, 3, "Ventile", false, true, TaskStatus.OVERDUE, null, null, null, null, null, null)),
        )
        return LogMaintenanceViewModel(
            3, vehicles, maintenance, savedState, measurements,
            today = { today }, shimDebounceMillis = debounce, decimalSeparator = ',',
        )
    }

    private fun LogMaintenanceViewModel.valves() = state.value.measurements.valves!!

    @Test fun loadsContextAndAddsTemplatesPrefilled() {
        measurements.contextResult = context()
        val vm = viewModel()
        assertEquals(listOf(3L), measurements.contextCalls)
        vm.addTemplate(MeasurementTemplate.VALVE_CLEARANCE)
        vm.addTemplate(MeasurementTemplate.CHAIN)
        assertEquals(listOf("2,5", ""), vm.valves().map { it.shimOld })
        assertEquals("20", vm.state.value.measurements.chain!!.wearLinks)
        vm.removeTemplate(MeasurementTemplate.CHAIN)
        assertNull(vm.state.value.measurements.chain)
    }

    @Test fun scannedWorksheetIsTakenOverOnlyForFittingTemplates() {
        measurements.contextResult = Outcome.Success(MeasurementContext(listOf(MeasurementTemplate.BRAKES), spec, latest))
        val vm = viewModel()
        vm.applyScan(ScanTransfer(MeasurementTemplate.CHAIN, values = mapOf("chain.slack" to "30")))
        assertNull(vm.state.value.measurements.chain)
        vm.applyScan(ScanTransfer(MeasurementTemplate.BRAKES, values = mapOf("brakes.padFront" to "4,5")))
        assertEquals("4,5", vm.state.value.measurements.brakes!!.padFront)
    }

    @Test fun scanIsIgnoredWithoutTheMeasurementContext() {
        measurements.contextResult = Outcome.Failure(AppError.Network)
        val vm = viewModel()
        vm.applyScan(ScanTransfer(MeasurementTemplate.BRAKES, values = mapOf("brakes.padFront" to "4,5")))
        assertNull(vm.state.value.measurements.brakes)
    }

    @Test fun templatesNotFittingTheVehicleAreNotAdded() {
        measurements.contextResult = Outcome.Success(MeasurementContext(listOf(MeasurementTemplate.TIRES), spec, latest))
        val vm = viewModel()
        vm.addTemplate(MeasurementTemplate.CHAIN)
        assertNull(vm.state.value.measurements.chain)
    }

    @Test fun loadFailureKeepsFormUsableAndCanBeRetried() {
        measurements.contextResult = Outcome.Failure(AppError.Network)
        val vm = viewModel()
        assertEquals(AppError.Network, vm.state.value.measurementLoadError)
        vm.addTemplate(MeasurementTemplate.BRAKES)
        assertNull(vm.state.value.measurements.brakes)
        measurements.contextResult = context()
        vm.retryMeasurements()
        assertNull(vm.state.value.measurementLoadError)
        assertTrue(vm.state.value.measurementContext != null)
        vm.retryMeasurements()
        assertEquals(2, measurements.contextCalls.size) // loaded: no further request
    }

    @Test fun shimSuggestionCanBeApplied() {
        measurements.contextResult = context()
        measurements.shimResult = Outcome.Success(listOf(suggestion, ShimSuggestion(null, null, null)))
        val vm = viewModel()
        vm.addTemplate(MeasurementTemplate.VALVE_CLEARANCE)
        vm.onValve(0, vm.valves()[0].copy(measured = "0,08"))
        assertEquals(
            listOf(ShimInput(ValveKind.INTAKE, BigDecimal("0.08"), BigDecimal("2.5")), ShimInput(ValveKind.EXHAUST, null, null)),
            measurements.shimCalls.last(),
        )
        assertEquals(suggestion, vm.state.value.shim.suggestions!![0])
        vm.applyShim(0)
        assertEquals("2,45", vm.valves()[0].shimNew)
        val calls = measurements.shimCalls.size
        vm.applyShim(1) // no suggestion for this row
        assertEquals("", vm.valves()[1].shimNew)
        assertEquals("a new shim does not change the inputs", calls, measurements.shimCalls.size)
    }

    @Test fun offlineKeepsInputAndReportsError() {
        measurements.contextResult = context()
        measurements.shimResult = Outcome.Failure(AppError.Network)
        val vm = viewModel()
        vm.addTemplate(MeasurementTemplate.VALVE_CLEARANCE)
        vm.onValve(0, vm.valves()[0].copy(measured = "0,08"))
        assertEquals(AppError.Network, vm.state.value.shim.error)
        assertNull(vm.state.value.shim.suggestions)
        assertEquals("0,08", vm.valves()[0].measured)
    }

    @Test fun noRequestWithoutCompleteTargets() {
        measurements.contextResult = context(spec.copy(shimStep = null))
        val vm = viewModel()
        vm.addTemplate(MeasurementTemplate.VALVE_CLEARANCE)
        vm.onValve(0, vm.valves()[0].copy(measured = "0,08"))
        assertTrue(measurements.shimCalls.isEmpty())
        assertEquals(ShimState(), vm.state.value.shim)
    }

    @Test fun shimRequestWaitsUntilTypingPauses() {
        measurements.contextResult = context()
        measurements.shimResult = Outcome.Success(listOf(suggestion, suggestion))
        val vm = viewModel(debounce = 400)
        vm.addTemplate(MeasurementTemplate.VALVE_CLEARANCE)
        vm.onValve(0, vm.valves()[0].copy(measured = "0,0"))
        dispatcher.scheduler.advanceTimeBy(200)
        vm.onValve(0, vm.valves()[0].copy(measured = "0,08"))
        dispatcher.scheduler.advanceTimeBy(399)
        assertTrue(measurements.shimCalls.isEmpty())
        dispatcher.scheduler.advanceTimeBy(2)
        assertEquals(1, measurements.shimCalls.size)
        assertEquals(BigDecimal("0.08"), measurements.shimCalls.single()[0].measured)
    }

    @Test fun removingValvesClearsSuggestions() {
        measurements.contextResult = context()
        measurements.shimResult = Outcome.Success(listOf(suggestion, suggestion))
        val vm = viewModel()
        vm.addTemplate(MeasurementTemplate.VALVE_CLEARANCE)
        vm.onValve(0, vm.valves()[0].copy(measured = "0,08"))
        vm.removeTemplate(MeasurementTemplate.VALVE_CLEARANCE)
        assertEquals(ShimState(), vm.state.value.shim)
    }

    @Test fun changeLayoutKeepsValues() {
        measurements.contextResult = context()
        val vm = viewModel()
        vm.addTemplate(MeasurementTemplate.VALVE_CLEARANCE)
        vm.onValve(0, vm.valves()[0].copy(measured = "0,12"))
        vm.changeLayout(2, 4)
        assertEquals(8, vm.valves().size)
        assertEquals("0,12", vm.valves()[0].measured)
    }

    @Test fun invalidMeasurementBlocksSave() {
        measurements.contextResult = context()
        val vm = viewModel()
        vm.addTemplate(MeasurementTemplate.TIRES)
        vm.onTires(vm.state.value.measurements.tires!!.copy(dotFront = "12"))
        assertFalse(vm.state.value.showMeasurementErrors)
        vm.save()
        assertEquals(LogError.Measurements, vm.state.value.error)
        assertTrue(vm.state.value.showMeasurementErrors)
        assertTrue(maintenance.logCalls.isEmpty())
    }

    @Test fun savesMeasurementsWithTheLog() {
        measurements.contextResult = context()
        maintenance.logResult = Outcome.Success(Unit)
        val vm = viewModel()
        vm.addTemplate(MeasurementTemplate.BRAKES)
        vm.onBrakes(vm.state.value.measurements.brakes!!.copy(padFront = "3,5", fluidCondition = " klar "))
        vm.save()
        val sent = maintenance.logCalls.single().measurements!!
        assertEquals(BigDecimal("3.5"), sent.brakes!!.padFront.value)
        assertEquals("klar", sent.brakes!!.fluidCondition)
        assertNull(sent.valves)
    }

    @Test fun editsAfterSavingAreIgnored() {
        measurements.contextResult = context()
        maintenance.logResult = Outcome.Success(Unit)
        val vm = viewModel()
        vm.addTemplate(MeasurementTemplate.BRAKES)
        vm.save()
        vm.addTemplate(MeasurementTemplate.TIRES)
        vm.onBrakes(BrakeDraft(padFront = "1"))
        assertNull(vm.state.value.measurements.tires)
        assertEquals("", vm.state.value.measurements.brakes!!.padFront)
    }

    @Test fun measurementsSurviveProcessDeath() {
        measurements.contextResult = context()
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        vm.addTemplate(MeasurementTemplate.CHAIN)
        vm.onChain(vm.state.value.measurements.chain!!.copy(slack = "30", cleaned = true))
        val restored = viewModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        assertEquals(vm.state.value.measurements, restored.state.value.measurements)
        assertEquals("30", restored.state.value.measurements.chain!!.slack)
    }

    @Test fun brokenSavedMeasurementsAreDropped() {
        val handle = SavedStateHandle(mapOf("log_selected" to longArrayOf(1), "log_measurements" to "{not json"))
        assertEquals(MeasurementDrafts(), viewModel(handle).state.value.measurements)
    }
}
