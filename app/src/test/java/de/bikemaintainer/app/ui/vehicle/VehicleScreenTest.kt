package de.bikemaintainer.app.ui.vehicle

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import de.bikemaintainer.app.domain.Access
import de.bikemaintainer.app.domain.AccessStatus
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.MaintenanceTask
import androidx.compose.ui.test.onAllNodesWithText
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleType
import de.bikemaintainer.app.ui.FakeMaintenanceRepository
import de.bikemaintainer.app.ui.FakeMeasurementRepository
import de.bikemaintainer.app.ui.FakeVehicleRepository
import de.bikemaintainer.app.ui.logmaintenance.LogResult
import java.time.LocalDate
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

    @Test fun readOnlyAccountShowsTheNoticeInsteadOfTheButtons() {
        show(
            VehicleUiState(
                vehicle = vehicle,
                tasks = listOf(task(1, "Ölwechsel", TaskStatus.OK)),
                access = Access(AccessStatus.EXPIRED),
            ),
        )
        compose.onNodeWithText(
            "Testphase abgelaufen – deine Daten bleiben erhalten, Erfassen ist nicht mehr möglich.",
        ).assertExists()
        compose.onNodeWithText("Kilometerstand eintragen").assertDoesNotExist()
        compose.onNodeWithText("Wartung erfassen").assertDoesNotExist()
        compose.onNodeWithText("Aufgabe hinzufügen").assertDoesNotExist()
        compose.onNodeWithText("Fahrzeug bearbeiten").assertDoesNotExist()
        compose.onNodeWithText("Fahrzeug löschen").performScrollTo().assertExists()
    }

    @Test fun whileDeletingTheActionsAreDisabled() {
        show(VehicleUiState(vehicle = vehicle, tasks = listOf(task(1, "Ölwechsel", TaskStatus.OK)), deleting = true))
        compose.onNodeWithText("Kilometerstand eintragen").assertIsNotEnabled()
        compose.onNodeWithText("Wartung erfassen").assertIsNotEnabled()
        compose.onNodeWithText("Aufgabe hinzufügen").assertIsNotEnabled()
    }

    @Test fun deletingAsksFirst() {
        var deleted = 0
        compose.setContent {
            VehicleContent(
                state = VehicleUiState(vehicle = vehicle, tasks = listOf(task(1, "Ölwechsel", TaskStatus.OK))),
                onBack = {},
                onRefresh = {},
                onRetry = {},
                actions = VehicleActions({}, {}, {}, {}, {}, onDeleteVehicle = { deleted++ }),
            )
        }
        compose.onNodeWithText("Fahrzeug löschen").performScrollTo().performClick()
        assertEquals(0, deleted)
        compose.onNodeWithText("Löschen").performClick()
        assertEquals(1, deleted)
    }

    @Test fun tapOnATaskOpensItsForm() {
        var edited: Long? = null
        compose.setContent {
            VehicleContent(
                state = VehicleUiState(
                    vehicle = vehicle,
                    tasks = listOf(task(1, "Ölwechsel", TaskStatus.OK)),
                    inactiveTasks = listOf(task(2, "Vergaser", TaskStatus.OK)),
                ),
                onBack = {},
                onRefresh = {},
                onRetry = {},
                actions = VehicleActions({}, {}, {}, {}, {}, onEditTask = { edited = it }),
            )
        }
        compose.onNodeWithText("Ölwechsel").performClick()
        assertEquals(1L, edited)
        // The inactive task sits below the fold of the lazy list: scroll the list to it first.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Vergaser"))
        compose.onNodeWithText("Vergaser").performClick()
        assertEquals(2L, edited)
    }

    @Test fun trialKeepsTheButtons() {
        show(
            VehicleUiState(
                vehicle = vehicle,
                tasks = listOf(task(1, "Ölwechsel", TaskStatus.OK)),
                access = Access(AccessStatus.TRIAL),
            ),
        )
        compose.onNodeWithText("Kilometerstand eintragen").assertExists()
        compose.onNodeWithText("Wartung erfassen").assertExists()
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
            FakeMeasurementRepository(),
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

    private fun showLogResult(result: LogResult) {
        val viewModel = VehicleViewModel(
            7,
            FakeVehicleRepository().apply { vehicleResult = Outcome.Success(vehicle) },
            FakeMaintenanceRepository(),
            FakeMeasurementRepository(),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VehicleScreen(viewModel = viewModel, onBack = {}, onLogMaintenance = {}, logResult = MutableStateFlow(result.name), onLogResultShown = {})
        }
        compose.mainClock.advanceTimeBy(6_000)
    }

    @Test fun savedSnackbarIsShort() {
        showLogResult(LogResult.SAVED)
        compose.onNodeWithText("Wartung gespeichert.").assertDoesNotExist()
    }

    @Test fun mileageFailedSnackbarStaysLonger() {
        showLogResult(LogResult.SAVED_MILEAGE_FAILED)
        compose.onNodeWithText("Wartung gespeichert, aber der Kilometerstand konnte nicht angehoben werden.").assertExists()
    }

    @Test @Config(qualifiers = "de-h1600dp") fun pendingEntriesSayWhatHappensAndOfferTheChoices() {
        val sent = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val state = VehicleUiState(
            vehicle = vehicle,
            tasks = listOf(
                de.bikemaintainer.app.domain.MaintenanceTask(1, 7, "Ölwechsel", false, true, TaskStatus.OK, null, null, null, null, null, null),
            ),
            pending = listOf(
                de.bikemaintainer.app.domain.PendingItem("m", 7, de.bikemaintainer.app.domain.PendingItem.Kind.MILEAGE, 12600),
                de.bikemaintainer.app.domain.PendingItem(
                    "d", 7, de.bikemaintainer.app.domain.PendingItem.Kind.MAINTENANCE, 12500, LocalDate.of(2026, 11, 9), setOf(1L),
                    de.bikemaintainer.app.domain.PendingState.PossibleDuplicate(LocalDate.of(2026, 11, 9)),
                ),
                de.bikemaintainer.app.domain.PendingItem(
                    "f", 7, de.bikemaintainer.app.domain.PendingItem.Kind.MILEAGE, 1,
                    state = de.bikemaintainer.app.domain.PendingState.Failed(de.bikemaintainer.app.domain.PendingProblem.MILEAGE_BELOW_CURRENT),
                ),
            ),
        )
        compose.setContent {
            VehicleContent(
                state = state, onBack = {}, onRefresh = {}, onRetry = {},
                actions = noActions.copy(onSendPendingAnyway = { sent += it }, onDiscardPending = { discarded += it }),
            )
        }
        compose.onNodeWithText("Noch nicht übertragen").assertExists()
        compose.onNodeWithText("Kilometerstand 12.600 km").assertExists()
        compose.onNodeWithText("Wird übertragen, sobald eine Verbindung besteht.").assertExists()
        // Once in the pending card, once as the task card below.
        compose.onAllNodesWithText("Ölwechsel", substring = true).assertCountEquals(2)
        compose.onNodeWithText("Trotzdem übertragen?", substring = true).assertExists()
        compose.onNodeWithText("Der Server hat bereits einen höheren Kilometerstand.").assertExists()
        compose.onNodeWithText("Trotzdem übertragen").performClick()
        compose.onAllNodesWithText("Verwerfen")[1].performClick()
        assertEquals(listOf("d"), sent)
        assertEquals(listOf("f"), discarded)
        // A waiting entry offers no buttons: two entries need a decision, so there are two discard buttons.
        compose.onAllNodesWithText("Verwerfen").assertCountEquals(2)
    }
}
