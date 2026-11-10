package de.bikemaintainer.app.ui.scan

import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.test.performScrollToNode
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
    fun multiPageSheet_showsProgressAndLeadsToTheNextPage() {
        val pages = ArrayDeque(listOf(valveSheetPage(1), valveSheetPage(3)))
        val vm = ScanViewModel(3, setOf(de.bikemaintainer.app.domain.MeasurementTemplate.VALVE_CLEARANCE), { ScanOutcome.Read(pages.removeFirst()) }, ',')
        var accepted: de.bikemaintainer.app.ui.logmaintenance.ScanTransfer? = null
        compose.setContent {
            val state by vm.state.collectAsState()
            val progress by vm.progress.collectAsState()
            ScanContent(state, {}, ScanActions(vm::onValue, vm::onChecked, vm::accept, vm::retake, vm::finish), progress) {
                Text("Kamera")
            }
            (state as? ScanUiState.Done)?.let { accepted = it.transfer }
        }
        compose.runOnIdle { vm.onPhoto(CROP) }
        compose.onNodeWithText("Ventilspiel · Seite 1 von 3").assertExists()
        acceptButton("Übernehmen, weiter mit Seite 2").performClick()
        compose.onNodeWithText("Kamera").assertExists()
        compose.onNodeWithText("Übernommene Seiten: 1 von 3").assertExists()
        compose.onNodeWithText("Jetzt Seite 2 fotografieren.").assertExists()
        compose.runOnIdle { vm.onPhoto(CROP) }
        // The review of the next page starts at the top again.
        compose.onNodeWithText("Ventilspiel · Seite 3 von 3").assertExists()
        acceptButton("Übernehmen, weiter mit Seite 2").performClick()
        compose.onNodeWithText("Übernommene Seiten: 1, 3 von 3").assertExists()
        compose.onNodeWithText("Fertig").performClick()
        compose.waitForIdle()
        assertEquals(5 * 3 * 3, accepted!!.values.size) // pages 1 and 3: cylinders 1–3 and 7–8
    }

    @Test
    fun singlePageSheet_goesStraightBackWithoutProgress() {
        val vm = ScanViewModel(3, setOf(de.bikemaintainer.app.domain.MeasurementTemplate.CHAIN), { ScanOutcome.Read(chainSheet()) }, ',')
        vm.onPhoto(CROP)
        var accepted: de.bikemaintainer.app.ui.logmaintenance.ScanTransfer? = null
        compose.setContent { ScanScreen(vm, onBack = {}, onAccepted = { accepted = it }) }
        compose.onNodeWithText("Übernommene Seiten", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Fertig").assertDoesNotExist()
        acceptButton("Übernehmen").performClick()
        compose.waitForIdle()
        assertEquals("32,5", accepted!!.values["chain.slack"])
        assertEquals(null, vm.progress.value)
    }

    @Test
    fun backDuringAMultiPageSheetHandsTheCollectedPagesOver() {
        val vm = ScanViewModel(3, setOf(de.bikemaintainer.app.domain.MeasurementTemplate.VALVE_CLEARANCE), { ScanOutcome.Read(valveSheetPage(1)) }, ',')
        var accepted: de.bikemaintainer.app.ui.logmaintenance.ScanTransfer? = null
        var backs = 0
        compose.setContent {
            ScanScreen(vm, onBack = { backs++ }, onAccepted = { accepted = it }, camera = { Text("Kamera") })
        }
        // Nothing taken over yet: back just leaves.
        compose.onNodeWithContentDescription("Zurück").performClick()
        assertEquals(1, backs)
        compose.runOnIdle { vm.onPhoto(CROP) }
        acceptButton("Übernehmen, weiter mit Seite 2").performClick()
        compose.onNodeWithText("Kamera").assertExists()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.waitForIdle()
        assertEquals(1, backs)
        assertEquals(3 * 3 * 3, accepted!!.values.size)
    }

    /** The accept button at the end of the (lazy) review list, scrolled into view. */
    private fun acceptButton(text: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }

    @Test
    fun valveFieldsAreLabelledLikeTheForm() {
        var label = ""
        compose.setContent { label = fieldLabel("valves.2.EXHAUST.1.shimNew") }
        assertEquals("Zyl. 2 Auslass 1 · Shim neu", label)
    }
}
