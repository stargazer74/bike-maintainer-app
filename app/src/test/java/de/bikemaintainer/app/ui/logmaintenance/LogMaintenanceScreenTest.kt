package de.bikemaintainer.app.ui.logmaintenance

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.state.ToggleableState
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleType
import de.bikemaintainer.app.ui.FakeMaintenanceRepository
import de.bikemaintainer.app.ui.FakeVehicleRepository
import de.bikemaintainer.app.ui.MainDispatcherRule
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-h1200dp")
class LogMaintenanceScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val mainRule = MainDispatcherRule()

    private val vehicles = FakeVehicleRepository()
    private val maintenance = FakeMaintenanceRepository()
    private val today = LocalDate.of(2026, 11, 9)
    private val vehicle = Vehicle(3, "Africa Twin", VehicleType.MOTORCYCLE, null, null, 2020, LocalDate.of(2020, 5, 1), 24500)
    private val done = mutableListOf<LogResult>()

    private fun task(id: Long, name: String, status: TaskStatus) =
        MaintenanceTask(id, 3, name, false, true, status, null, if (status == TaskStatus.OVERDUE) -100 else null, null, null, null, null)

    private fun show() {
        vehicles.vehicleResult = Outcome.Success(vehicle)
        vehicles.updateMileageResult = Outcome.Success(vehicle)
        maintenance.tasksResult = Outcome.Success(
            listOf(task(1, "Ölwechsel", TaskStatus.OVERDUE), task(2, "Kette", TaskStatus.DUE_SOON)),
        )
        val vm = LogMaintenanceViewModel(3, vehicles, maintenance) { today }
        compose.setContent { LogMaintenanceScreen(vm, onBack = {}, onDone = { done += it }) }
    }

    @Test fun logMaintenanceEndToEnd() {
        show()
        compose.onNode(hasText("Ölwechsel")).assertIsOn()
        compose.onNode(hasText("Kette")).assertIsOn()
        compose.onNode(hasText("Kette")).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Notiz (optional)")).performScrollTo().performTextInput("Neues Öl")
        compose.onNodeWithText("Material und Kosten in der Web-App ergänzen.").performScrollTo().assertExists()
        compose.onNodeWithText("Speichern").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, maintenance.logCalls.size)
        assertEquals(setOf(1L), maintenance.logCalls.single().taskIds)
        assertEquals("Neues Öl", maintenance.logCalls.single().notes)
        assertEquals(listOf(LogResult.SAVED), done)
    }

    @Test fun noTaskShowsError() {
        show()
        compose.onNode(hasText("Ölwechsel")).performClick()
        compose.onNode(hasText("Kette")).performClick()
        compose.onNodeWithText("Speichern").performScrollTo().performClick()
        compose.onNodeWithText("Bitte mindestens eine Aufgabe auswählen.").assertExists()
        assertEquals(0, maintenance.logCalls.size)
    }

    @Test fun raiseCheckboxAppearsForHigherMileage() {
        show()
        compose.onNodeWithText("Kilometerstand des Fahrzeugs auf 24.800 km anheben").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("Kilometerstand (km)")).performTextClearance()
        compose.onNode(hasSetTextAction() and hasText("Kilometerstand (km)")).performTextInput("24800")
        compose.onNodeWithText("Kilometerstand des Fahrzeugs auf 24.800 km anheben").assertIsOn()
    }

    @Test fun taskRowIsOneToggleableNode() {
        show()
        val node = compose.onNode(hasText("Ölwechsel") and hasText("Überfällig um 100 km"))
        node.assertIsOn()
        val state = node.fetchSemanticsNode().config[SemanticsProperties.ToggleableState]
        assertEquals(ToggleableState.On, state)
        assertEquals(Role.Checkbox, node.fetchSemanticsNode().config[SemanticsProperties.Role])
    }

    @Test fun onDoneIsCalledOnceEvenWhenRecomposed() {
        show()
        compose.onNodeWithText("Speichern").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnUiThread { }
        compose.waitForIdle()
        assertEquals(listOf(LogResult.SAVED), done)
    }
}
