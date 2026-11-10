package de.bikemaintainer.app.ui.scan

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import de.bikemaintainer.app.ui.MainDispatcherRule
import de.bikemaintainer.app.domain.scan.ScanOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-h1200dp")
class ScanScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val mainRule = MainDispatcherRule()

    private val noActions = ScanActions({ _, _ -> }, { _, _ -> }, {}, {})

    @Test
    fun cameraSlotIsShownFirst() {
        compose.setContent { ScanContent(ScanUiState.Camera, {}, noActions) { Text("Kamera") } }
        compose.onNodeWithText("Kamera").assertExists()
        compose.onNodeWithText("Arbeitsblatt fotografieren").assertExists()
    }

    @Test
    fun reviewShowsCropsValuesAndHighlightsUnsureOnes() {
        val vm = ScanViewModel(3, setOf(de.bikemaintainer.app.domain.MeasurementTemplate.CHAIN), { ScanOutcome.Read(chainSheet()) }, ',')
        vm.onPhoto(CROP)
        var accepted: Any? = null
        compose.setContent { ScanScreen(vm, onBack = {}, onAccepted = { accepted = it }) }
        compose.onNodeWithText("Kette · Seite 1 von 1").assertExists()
        compose.onNodeWithText("Bitte die markierten Werte (1) mit dem Blatt vergleichen.").assertExists()
        compose.onNodeWithText("Bitte prüfen").assertExists()
        compose.onNodeWithContentDescription("Foto des Felds Durchhang").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Durchhang")).performTextReplacement("33,5")
        compose.onNodeWithText("Bitte prüfen").assertDoesNotExist()
        compose.onNodeWithText("Gereinigt").assertIsOn()
        compose.onNodeWithText("Geschmiert").assertIsOff().performClick()
        compose.onNodeWithText("Übernehmen").performScrollTo().performClick()
        compose.waitForIdle()
        val transfer = accepted as de.bikemaintainer.app.ui.logmaintenance.ScanTransfer
        assertEquals("33,5", transfer.values["chain.slack"])
        assertEquals(true, transfer.checks["chain.lubricated"])
    }

    @Test
    fun problemsExplainWhatToDoAndOfferANewPhoto() {
        val messages = mapOf(
            ScanProblem.NO_SHEET to "Arbeitsblatt nicht erkannt. Alle vier schwarzen Eckquadrate müssen im Bild sein.",
            ScanProblem.OTHER_VEHICLE to "Dieses Arbeitsblatt gehört zu einem anderen Fahrzeug. Es wurde nichts übernommen.",
        )
        var state by androidx.compose.runtime.mutableStateOf<ScanUiState>(ScanUiState.Problem(ScanProblem.NO_SHEET))
        var retakes = 0
        compose.setContent { ScanContent(state, {}, noActions.copy(onRetake = { retakes++ })) { Text("Kamera") } }
        for ((problem, text) in messages) {
            state = ScanUiState.Problem(problem)
            compose.onNodeWithText(text).assertExists()
            compose.onNodeWithText("Neu fotografieren").performClick()
        }
        assertEquals(2, retakes)
        for (problem in ScanProblem.entries) {
            state = ScanUiState.Problem(problem)
            compose.waitForIdle()
        }
        state = ScanUiState.Reading
        compose.onNodeWithText("Arbeitsblatt wird gelesen …").assertExists()
        assertTrue(retakes == 2)
    }

    @Test
    fun valveFieldsAreLabelledLikeTheForm() {
        var label = ""
        compose.setContent { label = fieldLabel("valves.2.EXHAUST.1.shimNew") }
        assertEquals("Zyl. 2 Auslass 1 · Shim neu", label)
    }
}
