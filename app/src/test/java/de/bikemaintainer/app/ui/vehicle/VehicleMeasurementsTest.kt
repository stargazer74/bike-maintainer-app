package de.bikemaintainer.app.ui.vehicle

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import de.bikemaintainer.app.domain.BrakeMeasurement
import de.bikemaintainer.app.domain.ChainMeasurement
import de.bikemaintainer.app.domain.LatestMeasurements
import de.bikemaintainer.app.domain.Measured
import de.bikemaintainer.app.domain.MeasurementContext
import de.bikemaintainer.app.domain.MeasurementSource
import de.bikemaintainer.app.domain.MeasurementStatus
import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.Measurements
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Specification
import de.bikemaintainer.app.domain.TireMeasurement
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-h4000dp")
class VehicleMeasurementsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val mainRule = MainDispatcherRule()

    private val vehicle = Vehicle(7, "Africa Twin", VehicleType.MOTORCYCLE, null, null, 2020, LocalDate.of(2020, 5, 1), 12345)
    private val spec = Specification(
        cylinders = 2,
        valveAdjustment = ValveAdjustment.SHIM,
        intakeClearanceMin = BigDecimal("0.10"),
        intakeClearanceMax = BigDecimal("0.15"),
        shimStep = BigDecimal("0.050"),
        chainWearLinks = 20,
        chainWearMaxLength = BigDecimal("320"),
        tirePressureFront = BigDecimal("2.5"),
    )
    private val latest = LatestMeasurements(
        Measurements(
            valves = listOf(
                ValveMeasurement(1, ValveKind.INTAKE, 1, BigDecimal("0.08"), BigDecimal("2.55"), BigDecimal("2.45"), MeasurementStatus.OUT_OF_RANGE),
                ValveMeasurement(1, ValveKind.EXHAUST, 1, BigDecimal("0.22"), BigDecimal("2.60"), BigDecimal("2.60"), MeasurementStatus.OK),
            ),
            brakes = BrakeMeasurement(padFront = Measured(BigDecimal("3.5"), MeasurementStatus.OK), fluidCondition = "klar"),
            chain = ChainMeasurement(wearLinks = 20, cleaned = true, lubricated = false),
            tires = TireMeasurement(pressureFront = Measured(BigDecimal("2.4"))),
        ),
        mapOf(MeasurementTemplate.VALVE_CLEARANCE to MeasurementSource(9, LocalDate.of(2026, 10, 1), 24000)),
    )

    private fun show(state: VehicleUiState) = compose.setContent {
        VehicleContent(state = state, onBack = {}, onRefresh = {}, onRetry = {}, actions = VehicleActions({}, {}, {}, {}, {}))
    }

    @Test fun showsLatestMeasurementsAndTargets() {
        show(VehicleUiState(vehicle = vehicle, measurements = MeasurementContext(MeasurementTemplate.entries, spec, latest)))
        compose.onNodeWithText("Letzte Messwerte").performScrollTo()
        compose.onNodeWithText("Letzte Messung: 01.10.2026, 24.000 km").assertExists()
        compose.onNode(hasText("Zyl. 1 Einlass") and hasText("0,08 mm, Shim 2,55 → 2,45") and hasText("außerhalb Soll")).assertExists()
        compose.onNodeWithText("0,22 mm, Shim 2,6 (unverändert)").assertExists()
        compose.onNodeWithText("klar").assertExists()
        compose.onNodeWithText("2,4 bar").assertExists()
        compose.onNode(hasText("Gereinigt") and hasText("ja")).assertExists()
        compose.onNodeWithText("Geschmiert").assertDoesNotExist()
        compose.onNodeWithText("Sollwerte").performScrollTo()
        compose.onNodeWithText("0,1–0,15 mm").assertExists()
        compose.onNodeWithText("Shim (Tassenstößel)").assertExists()
        compose.onNodeWithText("0,05 mm").assertExists()
        compose.onNodeWithText("max. 320 mm über 20 Glieder").assertExists()
        compose.onNodeWithText("2,5 bar").assertExists()
        compose.onNodeWithText("Sollwerte werden in der Web-App gepflegt.").assertExists()
    }

    @Test fun emptyStatesExplainWhatToDo() {
        show(
            VehicleUiState(
                vehicle = vehicle,
                measurements = MeasurementContext(listOf(MeasurementTemplate.TIRES), Specification(), LatestMeasurements()),
            ),
        )
        compose.onNodeWithText("Noch keine Messwerte erfasst. Du trägst sie beim Erfassen einer Wartung ein.").performScrollTo()
        compose.onNodeWithText("Noch keine Sollwerte – trage sie in der Web-App unter „Sollwerte“ ein.").performScrollTo()
    }

    @Test fun nothingWithoutTemplates() {
        show(VehicleUiState(vehicle = vehicle, measurements = MeasurementContext(emptyList(), spec, latest)))
        compose.onNodeWithText("Letzte Messwerte").assertDoesNotExist()
        compose.onNodeWithText("Sollwerte").assertDoesNotExist()
    }

    @Test fun viewModelLoadsMeasurementsAfterEachLoadAndToleratesFailure() {
        val measurements = FakeMeasurementRepository().apply {
            contextResult = Outcome.Success(MeasurementContext(MeasurementTemplate.entries, spec, latest))
        }
        val vehicles = FakeVehicleRepository().apply { vehicleResult = Outcome.Success(vehicle) }
        val vm = VehicleViewModel(7, vehicles, FakeMaintenanceRepository(), measurements)
        assertEquals(listOf(7L), measurements.contextCalls)
        assertEquals(spec, vm.state.value.measurements!!.specification)
        measurements.contextResult = Outcome.Failure(de.bikemaintainer.app.domain.AppError.Network)
        vm.refresh()
        assertEquals(2, measurements.contextCalls.size)
        assertEquals("a failed reload keeps the last values", spec, vm.state.value.measurements!!.specification)
        vehicles.vehicleResult = Outcome.Failure(de.bikemaintainer.app.domain.AppError.NotFound)
        vm.refresh()
        assertNull(vm.state.value.measurements)
    }
}
