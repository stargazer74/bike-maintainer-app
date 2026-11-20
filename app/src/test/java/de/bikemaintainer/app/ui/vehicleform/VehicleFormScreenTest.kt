package de.bikemaintainer.app.ui.vehicleform

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleDraft
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
class VehicleFormScreenTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 20)
    private val draft = VehicleDraft.of(Vehicle(7, "Golf", VehicleType.CAR, "VW", null, 2015, LocalDate.of(2015, 3, 1), 99000))

    private fun show(state: VehicleFormUiState, onSave: () -> Unit = {}, onDelete: (() -> Unit)? = null) {
        compose.setContent {
            VehicleFormContent(
                state = state,
                today = today,
                onBack = {},
                onRetry = {},
                onEdit = {},
                onSave = onSave,
                onDelete = onDelete,
            )
        }
    }

    @Test fun deletingAsksFirst() {
        var deleted = 0
        show(VehicleFormUiState(editing = true, draft = draft), onDelete = { deleted++ })
        compose.onNodeWithText("Fahrzeug löschen").performScrollTo().performClick()
        assertEquals(0, deleted)
        compose.onNodeWithText("Löschen").performClick()
        assertEquals(1, deleted)
    }

    @Test fun cancellingKeepsTheVehicle() {
        var deleted = 0
        show(VehicleFormUiState(editing = true, draft = draft), onDelete = { deleted++ })
        compose.onNodeWithText("Fahrzeug löschen").performScrollTo().performClick()
        compose.onNodeWithText("Abbrechen").performClick()
        assertEquals(0, deleted)
    }

    @Test fun aNewVehicleCannotBeDeleted() {
        show(VehicleFormUiState(editing = false, draft = VehicleDraft.empty(today)), onDelete = {})
        compose.onNodeWithText("Fahrzeug löschen").assertDoesNotExist()
    }

    @Test fun readOnlyShowsTheDataWithoutSavingButKeepsDeleting() {
        show(VehicleFormUiState(editing = true, draft = draft, readOnly = true), onDelete = {})
        compose.onNodeWithText("Fahrzeugdaten").assertExists()
        compose.onNodeWithText(
            "Mit Lesezugriff kannst du die Fahrzeugdaten ansehen, aber nicht ändern. Löschen ist weiterhin möglich.",
        ).assertExists()
        compose.onNodeWithText("Speichern").assertDoesNotExist()
        compose.onNodeWithText("Motorrad").assertIsNotEnabled()
        compose.onNodeWithText("Fahrzeug löschen").performScrollTo().assertExists()
    }
}
