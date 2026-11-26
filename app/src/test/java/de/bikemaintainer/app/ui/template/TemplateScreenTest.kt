package de.bikemaintainer.app.ui.template

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TemplateApplyResult
import de.bikemaintainer.app.domain.TemplateSourceQuality
import de.bikemaintainer.app.domain.TemplateSummary
import de.bikemaintainer.app.domain.TemplateTask
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleTemplate
import de.bikemaintainer.app.domain.VehicleType
import de.bikemaintainer.app.ui.FakeMaintenanceRepository
import de.bikemaintainer.app.ui.FakeTemplateRepository
import de.bikemaintainer.app.ui.FakeVehicleRepository
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-h2400dp")
class TemplateScreenTest {
    @get:Rule val compose = createComposeRule()

    private val vehicle = Vehicle(7, "Hornet", VehicleType.MOTORCYCLE, "Honda", "CB 650 R", 2020, LocalDate.of(2020, 5, 1), 0)
    private val vehicles = FakeVehicleRepository().also { it.vehicleResult = Outcome.Success(vehicle) }
    private val templates = FakeTemplateRepository().also {
        val task = TemplateTask("oil", "Ölwechsel", null, false, 6000, 12, null, null, emptyList(), emptyList())
        it.searchResult = Outcome.Success(
            listOf(TemplateSummary("t", VehicleType.MOTORCYCLE, "Honda", "CB 650 R", 2015, null, 1, TemplateSourceQuality.MANUFACTURER)),
        )
        it.templates["t"] = VehicleTemplate(
            "t", VehicleType.MOTORCYCLE, "Honda", "CB 650 R", 2015, null, 1, TemplateSourceQuality.MANUFACTURER, null,
            emptyList(), listOf(task),
        )
        it.applyResult = Outcome.Success(TemplateApplyResult(1, 0))
    }

    /** Shows the screen; with [open] the user also picks the single search hit. */
    private fun show(open: Boolean = true, onApplied: (TemplateApplyResult) -> Unit = {}) {
        val viewModel = TemplateViewModel(7, vehicles, FakeMaintenanceRepository().also { it.tasksResult = Outcome.Success(emptyList()) }, templates)
        compose.setContent { TemplateScreen(viewModel, onBack = {}, onApplied = onApplied) }
        if (open) compose.onNodeWithText("Honda CB 650 R").performClick()
    }

    @Test fun aSingleHitIsListedAndOpensOnlyWhenPicked() {
        show(open = false)
        compose.onNodeWithText("Vorlage wählen").assertExists()
        compose.onNodeWithText("Ölwechsel").assertDoesNotExist()
        compose.onNodeWithText("Honda CB 650 R").performClick()
        compose.onNodeWithText("Ölwechsel").assertExists()
    }

    @Test fun disclaimerStandsAboveTheConfirmationAndApplyNeedsIt() {
        var applied: TemplateApplyResult? = null
        show { applied = it }
        compose.onNodeWithText(
            "Die Vorlagen sind Richtwerte ohne Gewähr. Wir übernehmen keine Haftung für die Richtigkeit der Daten; " +
                "die Nutzung erfolgt auf eigene Gefahr. Maßgeblich ist das Handbuch deines Fahrzeugs.",
        ).performScrollTo().assertExists()
        compose.onNodeWithText("Übernehmen").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Ich habe den Hinweis gelesen.").performScrollTo().performClick()
        compose.onNodeWithText("Übernehmen").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(TemplateApplyResult(1, 0), applied)
    }

    @Test fun noLastDoneFieldsForAVehicleWithoutMileage() {
        show()
        compose.onNodeWithText("Ölwechsel").assertExists()
        compose.onNodeWithText("Zuletzt erledigt").assertDoesNotExist()
        compose.onNodeWithText("Leere Felder: aktueller Stand und heute").assertDoesNotExist()
    }
}
