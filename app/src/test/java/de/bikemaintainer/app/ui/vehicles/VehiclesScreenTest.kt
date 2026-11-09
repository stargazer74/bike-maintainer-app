package de.bikemaintainer.app.ui.vehicles

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        onOpenWebApp: () -> Unit = {},
    ) {
        compose.setContent {
            VehiclesContent(
                state = state,
                onRefresh = {},
                onRetry = onRetry,
                onOpenVehicle = onOpenVehicle,
                onOpenSettings = {},
                onOpenWebApp = onOpenWebApp,
            )
        }
    }

    @Test fun cardShowsNameMileageAndStatus() {
        show(VehiclesUiState(summaries = listOf(summary), loading = false))
        compose.onNodeWithText("Africa Twin").assertExists()
        compose.onNodeWithText("12.345 km").assertExists()
        compose.onNodeWithText("Überfällig").assertExists()
        compose.onNodeWithText("Fällig: 2").assertExists()
    }

    @Test fun cardWithoutTasksSaysSo() {
        show(VehiclesUiState(summaries = listOf(summary.copy(status = null, dueCount = 0)), loading = false))
        compose.onNodeWithText("Keine Wartungsaufgaben").assertExists()
    }

    @Test fun emptyListOffersWebApp() {
        var opened = 0
        show(VehiclesUiState(summaries = emptyList(), loading = false, webAppUrl = "https://h"), onOpenWebApp = { opened++ })
        compose.onNodeWithText("Noch keine Fahrzeuge erfasst. Lege sie in der Web-App an.").assertExists()
        compose.onNodeWithText("Web-App öffnen").performClick()
        assertEquals(1, opened)
    }

    @Test fun errorOffersRetry() {
        var retried = 0
        show(VehiclesUiState(loading = false, error = AppError.Network), onRetry = { retried++ })
        compose.onNodeWithText("Keine Verbindung.").assertExists()
        compose.onNodeWithText("Erneut versuchen").performClick()
        assertEquals(1, retried)
    }

    @Test fun errorWithDataShowsListAndMessage() {
        show(VehiclesUiState(summaries = listOf(summary), loading = false, error = AppError.Network))
        compose.onNodeWithText("Keine Verbindung.").assertExists()
        compose.onNodeWithText("Africa Twin").assertExists()
    }

    @Test fun tappingCardOpensVehicle() {
        var opened: Long? = null
        show(VehiclesUiState(summaries = listOf(summary), loading = false), onOpenVehicle = { opened = it })
        compose.onNodeWithText("Africa Twin").performClick()
        assertEquals(7L, opened)
    }
}
