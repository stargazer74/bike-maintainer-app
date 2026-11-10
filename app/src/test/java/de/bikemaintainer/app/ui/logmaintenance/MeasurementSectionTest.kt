package de.bikemaintainer.app.ui.logmaintenance

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.LatestMeasurements
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.MeasurementContext
import de.bikemaintainer.app.domain.MeasurementSource
import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.Measurements
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.ShimHint
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-h4000dp")
class MeasurementSectionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val mainRule = MainDispatcherRule()

    private val vehicles = FakeVehicleRepository()
    private val maintenance = FakeMaintenanceRepository()
    private val measurements = FakeMeasurementRepository()
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
        treadDepthMin = BigDecimal("2"),
    )
    private val latest = LatestMeasurements(
        Measurements(valves = listOf(ValveMeasurement(1, ValveKind.INTAKE, 1, BigDecimal("0.12"), BigDecimal("2.55"), null))),
        mapOf(MeasurementTemplate.VALVE_CLEARANCE to MeasurementSource(9, LocalDate.of(2026, 10, 1), 24000)),
    )

    private fun show(context: Outcome<MeasurementContext> = Outcome.Success(MeasurementContext(MeasurementTemplate.entries, spec, latest))): LogMaintenanceViewModel {
        vehicles.vehicleResult = Outcome.Success(vehicle)
        maintenance.tasksResult = Outcome.Success(
            listOf(MaintenanceTask(1, 3, "Ventile", false, true, TaskStatus.OVERDUE, null, -10, null, null, null, null)),
        )
        measurements.contextResult = context
        val vm = LogMaintenanceViewModel(
            3, vehicles, maintenance, SavedStateHandle(), measurements,
            today = { LocalDate.of(2026, 11, 9) }, shimDebounceMillis = 0, decimalSeparator = ',',
        )
        compose.setContent { LogMaintenanceScreen(vm, onBack = {}, onDone = {}) }
        return vm
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label, substring = true))

    @Test fun valveClearanceWithShimSuggestion() {
        measurements.shimResult = Outcome.Success(
            listOf(ShimSuggestion(BigDecimal("2.45"), BigDecimal("0.13"), null), ShimSuggestion(null, null, ShimHint.MISSING_INPUT)),
        )
        val vm = show()
        compose.onNodeWithText("Messwerte (optional)").assertExists()
        compose.onNodeWithText("Ventilspiel hinzufügen").performScrollTo().performClick()
        compose.onNodeWithText("Letzte Messung: 01.10.2026, 24.000 km").assertExists()
        compose.onNodeWithText("Zyl. 1 Einlass").assertExists()
        compose.onNodeWithText("Soll 0,1–0,15 · zuletzt 0,12").assertExists()
        compose.onAllNodes(hasSetTextAction() and hasText("Ist (mm)")).onFirst().performTextInput("0,08")
        compose.onNodeWithText("Soll 0,1–0,15 · zuletzt 0,12 · außerhalb Soll").assertExists()
        compose.onNodeWithText("Vorschlag Shim 2,45, Spiel danach 0,13").assertExists()
        compose.onNodeWithText("Übernehmen").performClick()
        assertEquals("2,45", vm.state.value.measurements.valves!![0].shimNew)
        compose.onNodeWithText("Übernehmen").assertIsNotEnabled()
    }

    @Test fun offlineMessageKeepsInput() {
        measurements.shimResult = Outcome.Failure(AppError.Network)
        show()
        compose.onNodeWithText("Ventilspiel hinzufügen").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction() and hasText("Ist (mm)")).onFirst().performTextInput("0,08")
        compose.onNodeWithText("Ohne Verbindung kein Shim-Vorschlag – deine Eingaben bleiben erhalten.").assertExists()
        compose.onNodeWithText("0,08").assertExists()
    }

    @Test fun missingTargetsExplainWhereToEnterThem() {
        show(Outcome.Success(MeasurementContext(listOf(MeasurementTemplate.VALVE_CLEARANCE), Specification(), LatestMeasurements())))
        compose.onNodeWithText("Ventilspiel hinzufügen").performScrollTo().performClick()
        compose.onNodeWithText("Für einen Shim-Vorschlag", substring = true).assertExists()
        // Default layout without vehicle data or earlier measurements: 2 cylinders × 4 valves.
        compose.onNodeWithText("Zyl. 2 Auslass 2").assertExists()
    }

    @Test fun invalidValueIsShownAfterSave() {
        show()
        compose.onNodeWithText("Reifen hinzufügen").performScrollTo().performClick()
        field("DOT vorne").performTextInput("12")
        compose.onNodeWithText("Vier Ziffern: Woche und Jahr, z. B. 2324.").assertDoesNotExist()
        compose.onNodeWithText("Speichern").performScrollTo().performClick()
        compose.onNodeWithText("Vier Ziffern: Woche und Jahr, z. B. 2324.").assertExists()
        compose.onNodeWithText("Bitte korrigiere die markierten Messwerte.").assertExists()
        assertEquals(0, maintenance.logCalls.size)
    }

    @Test fun treadBelowMinimumIsMarked() {
        show()
        compose.onNodeWithText("Reifen hinzufügen").performScrollTo().performClick()
        field("Profil vorne").performTextInput("1,5")
        compose.onNodeWithText("Soll min. 2 · außerhalb Soll").assertExists()
    }

    @Test fun loadErrorOffersRetry() {
        show(Outcome.Failure(AppError.Network))
        compose.onNodeWithText("Vorlagen und Sollwerte konnten nicht geladen werden.").assertExists()
        measurements.contextResult = Outcome.Success(MeasurementContext(listOf(MeasurementTemplate.CHAIN), spec, latest))
        compose.onAllNodesWithText("Erneut versuchen").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Kette hinzufügen").assertExists()
    }

    @Test fun noSectionWithoutTemplates() {
        show(Outcome.Success(MeasurementContext(emptyList(), spec, latest)))
        compose.onNodeWithText("Messwerte (optional)").assertDoesNotExist()
    }

    @Test fun brakesAndChainCanBeEnteredAndRemoved() {
        val vm = show()
        compose.onNodeWithText("Bremsen hinzufügen").performScrollTo().performClick()
        field("Belag vorne").performTextInput("3,5")
        field("Bremsflüssigkeit").performTextInput("klar")
        compose.onNodeWithText("Kette hinzufügen").performScrollTo().performClick()
        field("Durchhang").performTextInput("30")
        compose.onNodeWithText("Gereinigt").performScrollTo().performClick()
        val drafts = vm.state.value.measurements
        assertEquals("3,5", drafts.brakes!!.padFront)
        assertEquals("klar", drafts.brakes!!.fluidCondition)
        assertEquals(true, drafts.chain!!.cleaned)
        compose.onNodeWithText("Kette entfernen").performScrollTo().performClick()
        assertEquals(null, vm.state.value.measurements.chain)
    }

    @Test @Config(qualifiers = "en-h4000dp") fun buttonTextsReadNaturallyInEnglish() {
        show()
        compose.onNodeWithText("Add valve clearance").assertExists()
        compose.onNodeWithText("Add tyres").performScrollTo().performClick()
        compose.onNodeWithText("Remove tyres").assertExists()
    }
}
