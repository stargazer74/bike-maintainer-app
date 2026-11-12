package de.bikemaintainer.app.ui.vehicles

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.bikemaintainer.app.domain.Access
import de.bikemaintainer.app.domain.AccessStatus
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleSummary
import de.bikemaintainer.app.domain.VehicleType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de")
class VehiclesScreenTest {
    @get:Rule val compose = createComposeRule()

    private val summary = VehicleSummary(
        Vehicle(7, "Africa Twin", VehicleType.MOTORCYCLE, null, null, 2020, LocalDate.of(2020, 5, 1), 12345),
        TaskStatus.OVERDUE,
        2,
    )

    private fun show(
        state: VehiclesUiState,
        onRetry: () -> Unit = {},
        onOpenVehicle: (Long) -> Unit = {},
        onAddVehicle: () -> Unit = {},
    ) {
        compose.setContent {
            VehiclesContent(
                state = state,
                onRefresh = {},
                onRetry = onRetry,
                onOpenVehicle = onOpenVehicle,
                onOpenSettings = {},
                onAddVehicle = onAddVehicle,
            )
        }
    }

    @Test fun cardShowsNameMileageAndStatus() {
        show(VehiclesUiState(summaries = listOf(summary)))
        compose.onNodeWithText("Africa Twin").assertExists()
        compose.onNodeWithText("12.345 km").assertExists()
        compose.onNodeWithText("Überfällig").assertExists()
        compose.onNodeWithText("Fällig: 2").assertExists()
    }

    @Test fun statusIsCarriedByTheTextNotTheDot() {
        val ok = summary.copy(vehicle = summary.vehicle.copy(id = 8, name = "Golf"), status = TaskStatus.OK, dueCount = 0)
        show(VehiclesUiState(summaries = listOf(summary, ok)))
        compose.onAllNodesWithContentDescription("Überfällig").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("OK").assertCountEquals(0)
        compose.onNodeWithText("Alles aktuell").assertExists()
    }

    @Test fun notFoundInTheListIsAGenericLoadError() {
        show(VehiclesUiState(error = AppError.NotFound))
        compose.onNodeWithText("Daten konnten nicht geladen werden.").assertExists()
        compose.onNodeWithText("Das Fahrzeug wurde nicht gefunden.").assertDoesNotExist()
    }

    @Test fun cardWithoutTasksSaysSo() {
        show(VehiclesUiState(summaries = listOf(summary.copy(status = null, dueCount = 0))))
        compose.onNodeWithText("Keine Wartungsaufgaben").assertExists()
    }

    @Test fun emptyListOffersTheFirstVehicle() {
        var added = 0
        show(VehiclesUiState(summaries = emptyList()), onAddVehicle = { added++ })
        compose.onNodeWithText("Noch keine Fahrzeuge erfasst.").assertExists()
        compose.onNodeWithText("Erstes Fahrzeug anlegen").performClick()
        assertEquals(1, added)
    }

    @Test fun listOffersAnotherVehicle() {
        var added = 0
        show(VehiclesUiState(summaries = listOf(summary)), onAddVehicle = { added++ })
        compose.onNodeWithText("Fahrzeug hinzufügen").performClick()
        assertEquals(1, added)
    }

    @Test fun readOnlyAccountCannotAddVehicles() {
        show(VehiclesUiState(summaries = emptyList(), access = Access(AccessStatus.EXPIRED)))
        compose.onNodeWithText("Erstes Fahrzeug anlegen").assertDoesNotExist()
        compose.onNodeWithText(
            "Testphase abgelaufen – deine Daten bleiben erhalten, Erfassen ist nicht mehr möglich.",
        ).assertExists()
    }

    @Test fun errorOffersRetry() {
        var retried = 0
        show(VehiclesUiState(error = AppError.Network), onRetry = { retried++ })
        compose.onNodeWithText("Keine Verbindung.").assertExists()
        compose.onNodeWithText("Erneut versuchen").performClick()
        assertEquals(1, retried)
    }

    @Test fun errorWithDataShowsListAndMessage() {
        show(VehiclesUiState(summaries = listOf(summary), error = AppError.Network))
        compose.onNodeWithText("Keine Verbindung.").assertExists()
        compose.onNodeWithText("Africa Twin").assertExists()
    }

    @Test fun tappingCardOpensVehicle() {
        var opened: Long? = null
        show(VehiclesUiState(summaries = listOf(summary)), onOpenVehicle = { opened = it })
        compose.onNodeWithText("Africa Twin").performClick()
        assertEquals(7L, opened)
    }
}
