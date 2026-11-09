package de.bikemaintainer.app.ui.vehicle

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleType
import java.time.LocalDate
import de.bikemaintainer.app.ui.FakeMaintenanceRepository
import de.bikemaintainer.app.ui.FakeVehicleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de")
class VehicleScreenTest {
    @get:Rule val compose = createComposeRule()

    private val vehicle = Vehicle(7, "Africa Twin", VehicleType.MOTORCYCLE, "Honda", "CRF1100", 2020, LocalDate.of(2020, 5, 1), 12345)

    private fun task(
        id: Long,
        name: String,
        status: TaskStatus,
        km: Int? = null,
        days: Int? = null,
        progress: Double? = null,
        lastDate: LocalDate? = null,
    ) = MaintenanceTask(id, 7, name, false, true, status, progress, km, days, null, lastDate, null)

    private fun show(state: VehicleUiState, onBack: () -> Unit = {}, onRetry: () -> Unit = {}) {
        compose.setContent {
            VehicleContent(state = state, onBack = onBack, onRefresh = {}, onRetry = onRetry, actions = noActions)
        }
    }

    private val noActions = VehicleActions({}, {}, {}, {}, {})

    @Test @Config(qualifiers = "de-h1200dp") fun showsHeaderAndTaskTexts() {
        show(
            VehicleUiState(
                vehicle = vehicle,
                tasks = listOf(
                    task(1, "Ölwechsel", TaskStatus.OVERDUE, km = -350, progress = 1.0),
                    task(2, "Kette", TaskStatus.OK, days = 1),
                    task(3, "Bremsen", TaskStatus.DUE_SOON, days = 12, progress = 0.9),
                ),
            ),
        )
        compose.onNodeWithText("12.345 km").assertExists()
        compose.onNodeWithText("Honda CRF1100 • Baujahr 2020").assertExists()
        compose.onNodeWithText("Überfällig um 350 km").assertExists()
        compose.onNodeWithText("Erstfällig in 1 Tag").assertExists()
        compose.onNodeWithText("Fällig in 12 Tagen").assertExists()
    }

    @Test fun statusIsReadOnceAndEachTaskIsOneStop() {
        show(VehicleUiState(vehicle = vehicle, tasks = listOf(task(1, "Ölwechsel", TaskStatus.OVERDUE, km = -350))))
        compose.onAllNodesWithContentDescription("Überfällig").assertCountEquals(0)
        compose.onNode(hasText("Ölwechsel") and hasText("Überfällig um 350 km")).assertExists()
    }

    @Test fun overdueByOneDayIsSingular() {
        show(VehicleUiState(vehicle = vehicle, tasks = listOf(task(1, "Kette", TaskStatus.OVERDUE, days = -1))))
        compose.onNodeWithText("Überfällig seit 1 Tag").assertExists()
    }

    @Test fun doneOnDateInGerman() {
        show(VehicleUiState(vehicle = vehicle, tasks = listOf(doneTask)))
        compose.onNodeWithText("Erledigt am 20.10.2026").assertExists()
    }

    @Test @Config(qualifiers = "en") fun doneOnDateInEnglish() {
        show(VehicleUiState(vehicle = vehicle, tasks = listOf(doneTask)))
        compose.onNodeWithText("Done on Oct 20, 2026").assertExists()
    }

    private val doneTask get() = task(1, "Reifen", TaskStatus.DONE, lastDate = LocalDate.of(2026, 10, 20))

    @Test fun noTasksShowsHint() {
        show(VehicleUiState(vehicle = vehicle))
        compose.onNodeWithText("Noch keine Wartungsaufgaben angelegt.").assertExists()
    }

    @Test fun errorOffersRetry() {
        var retried = 0
        show(VehicleUiState(error = AppError.Network), onRetry = { retried++ })
        compose.onNodeWithText("Keine Verbindung.").assertExists()
        compose.onNodeWithText("Erneut versuchen").performClick()
        assertEquals(1, retried)
    }

    @Test fun notFoundOffersBackInsteadOfRetry() {
        var back = 0
        show(VehicleUiState(error = AppError.NotFound), onBack = { back++ })
        compose.onNodeWithText("Das Fahrzeug wurde nicht gefunden.").assertExists()
        compose.onNodeWithText("Erneut versuchen").assertDoesNotExist()
        compose.onNodeWithText("Zurück").performClick()
        assertEquals(1, back)
    }

    @Test fun enterMileageDialogSavesAndShowsTooLow() {
        val viewModel = VehicleViewModel(
            7,
            FakeVehicleRepository().apply { vehicleResult = Outcome.Success(vehicle.copy(currentMileage = 24500)) },
            FakeMaintenanceRepository(),
        )
        compose.setContent { VehicleScreen(viewModel = viewModel, onBack = {}, onLogMaintenance = {}, logResult = MutableStateFlow(null), onLogResultShown = {}) }
        compose.onNodeWithText("Kilometerstand eintragen").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("24000")
        compose.onNodeWithText("Speichern").performClick()
        compose.onNodeWithText("Darf nicht kleiner sein als bisher (24.500 km).").assertExists()
    }

    @Test fun logButtonHiddenWithoutTasks() {
        show(VehicleUiState(vehicle = vehicle))
        compose.onNodeWithText("Kilometerstand eintragen").assertExists()
        compose.onNodeWithText("Wartung erfassen").assertDoesNotExist()
    }

    @Test fun logButtonShownWithTasks() {
        var logged = 0
        compose.setContent {
            VehicleContent(
                state = VehicleUiState(vehicle = vehicle, tasks = listOf(task(1, "Kette", TaskStatus.OK))),
                onBack = {}, onRefresh = {}, onRetry = {},
                actions = noActions.copy(onLogMaintenance = { logged++ }),
            )
        }
        compose.onNodeWithText("Wartung erfassen").performClick()
        assertEquals(1, logged)
    }
}
