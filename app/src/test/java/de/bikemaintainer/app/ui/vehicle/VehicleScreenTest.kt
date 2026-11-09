package de.bikemaintainer.app.ui.vehicle

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
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
class VehicleScreenTest {
    @get:Rule val compose = createComposeRule()

    private val vehicle = Vehicle(7, "Africa Twin", VehicleType.MOTORCYCLE, "Honda", "CRF1100", 2020, LocalDate.of(2020, 5, 1), 12345)

    private fun task(id: Long, name: String, status: TaskStatus, km: Int? = null, days: Int? = null, progress: Double? = null) =
        MaintenanceTask(id, 7, name, false, true, status, progress, km, days, null, null, null)

    private fun show(state: VehicleUiState, onBack: () -> Unit = {}, onRetry: () -> Unit = {}) {
        compose.setContent { VehicleContent(state = state, onBack = onBack, onRefresh = {}, onRetry = onRetry) }
    }

    @Test fun showsHeaderAndTaskTexts() {
        show(
            VehicleUiState(
                vehicle = vehicle,
                loading = false,
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

    @Test fun noTasksShowsHint() {
        show(VehicleUiState(vehicle = vehicle, loading = false))
        compose.onNodeWithText("Noch keine Wartungsaufgaben angelegt.").assertExists()
    }

    @Test fun errorOffersRetry() {
        var retried = 0
        show(VehicleUiState(loading = false, error = AppError.Network), onRetry = { retried++ })
        compose.onNodeWithText("Keine Verbindung.").assertExists()
        compose.onNodeWithText("Erneut versuchen").performClick()
        assertEquals(1, retried)
    }

    @Test fun notFoundOffersBackInsteadOfRetry() {
        var back = 0
        show(VehicleUiState(loading = false, error = AppError.NotFound), onBack = { back++ })
        compose.onNodeWithText("Das Fahrzeug wurde nicht gefunden.").assertExists()
        compose.onNodeWithText("Erneut versuchen").assertDoesNotExist()
        compose.onNodeWithText("Zurück").performClick()
        assertEquals(1, back)
    }
}
