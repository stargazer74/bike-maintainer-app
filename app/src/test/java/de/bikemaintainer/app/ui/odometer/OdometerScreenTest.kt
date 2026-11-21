package de.bikemaintainer.app.ui.odometer

import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import de.bikemaintainer.app.domain.odometer.OdoReading
import de.bikemaintainer.app.domain.odometer.Point
import de.bikemaintainer.app.domain.scan.GrayImage
import de.bikemaintainer.app.ui.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-h1200dp")
class OdometerScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val mainRule = MainDispatcherRule()

    private val photo = GrayImage(800, 600)

    @Test
    fun theCameraComesFirst() {
        compose.setContent { OdometerContent(OdometerUiState.Camera, {}, OdometerActions()) { Text("Kamera") } }
        compose.onNodeWithText("Kamera").assertExists()
        compose.onNodeWithText("Tacho fotografieren").assertExists()
    }

    @Test
    fun theReviewShowsPhotoLineAndValueAndTakesTheCorrectedValueOver() {
        val vm = OdometerViewModel(1_000, FakeOdometerScanner(reading = OdoReading(1234, true)))
        vm.onPhoto(photo)
        var accepted: Int? = null
        compose.setContent { OdometerScreen(vm, onBack = {}, onAccepted = { accepted = it }) { Text("Kamera") } }
        compose.onNodeWithText("Der Wert ist unsicher – bitte mit dem Tacho vergleichen.").assertExists()
        compose.onNodeWithContentDescription("Foto des Tachos, die gelesene Zeile ist eingerahmt").assertExists()
        compose.onNodeWithContentDescription("Die gelesene Zeile, gerade gerückt").assertExists()
        compose.onNodeWithText("Bitte prüfen").assertExists()
        val field = compose.onNode(hasSetTextAction() and hasText("Kilometerstand (km)"))
        field.performTextReplacement("")
        compose.onNodeWithText("Übernehmen").assertIsNotEnabled()
        field.performTextReplacement("12345")
        compose.onNodeWithText("Bitte prüfen").assertDoesNotExist()
        compose.onNodeWithText("Vergleiche den Wert mit dem Tacho, bevor du ihn übernimmst.").assertExists()
        compose.onNodeWithText("Übernehmen").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(12_345, accepted)
    }

    @Test
    fun aFrameDrawnOnThePhotoIsRead() {
        val scanner = FakeOdometerScanner(line = null)
        val vm = OdometerViewModel(1_000, scanner)
        vm.onPhoto(photo)
        compose.setContent { OdometerScreen(vm, onBack = {}, onAccepted = {}) { Text("Kamera") } }
        compose.onNodeWithText(
            "Auf dem Foto wurde kein Gesamtkilometerstand gefunden. Wähle den Ausschnitt selbst oder fotografiere neu.",
        ).assertExists()
        compose.onNodeWithText("Ausschnitt selbst wählen").performClick()
        compose.onNodeWithText("Diesen Ausschnitt lesen").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Foto des Tachos; zieh einen Rahmen um den Gesamtkilometerstand")
            .performTouchInput { swipe(Offset(width * 0.2f, height * 0.4f), Offset(width * 0.7f, height * 0.6f), 300) }
        compose.onNodeWithText("Diesen Ausschnitt lesen").assertIsEnabled().performClick()
        compose.waitForIdle()
        val corners = scanner.reads.single().first
        // Drawn on the screen, read on the photo: roughly 20–70 % of the width, 40–60 % of the height.
        assertTrue(corners[0].x in 140.0..180.0 && corners[0].y in 220.0..260.0)
        assertTrue(corners[2].x in 540.0..580.0 && corners[2].y in 340.0..380.0)
        compose.onNode(hasSetTextAction() and hasText("12400")).assertExists()
    }

    @Test
    fun aFailedPhotoOnlyOffersANewOne() {
        var retakes = 0
        compose.setContent {
            OdometerContent(OdometerUiState.Problem(OdometerProblem.FAILED, null), {}, OdometerActions(onRetake = { retakes++ })) {}
        }
        compose.onNodeWithText("Das Foto konnte nicht gelesen werden. Bitte noch einmal versuchen.").assertExists()
        compose.onNodeWithText("Ausschnitt selbst wählen").assertDoesNotExist()
        compose.onNodeWithText("Neu fotografieren").performClick()
        assertEquals(1, retakes)
    }

    @Test
    fun aFrameNeedsSomeSize() {
        assertNull(frameOf(Offset(10f, 10f), Offset(30f, 60f)))
        assertNull(frameOf(null, Offset(30f, 60f)))
        val frame = frameOf(Offset(200f, 80f), Offset(100f, 40f))
        assertNotNull(frame)
        assertEquals(listOf(Point(100.0, 40.0), Point(200.0, 40.0), Point(200.0, 80.0), Point(100.0, 80.0)), frame)
    }

    @Test
    fun theReviewButtonsLeadToAdjustingAndANewPhoto() {
        var adjusted = 0
        var retakes = 0
        val review = OdometerUiState.Review(photo, listOf(Point(1.0, 1.0), Point(9.0, 1.0), Point(9.0, 5.0), Point(1.0, 5.0)), null, "12400", false)
        compose.setContent {
            OdometerContent(review, {}, OdometerActions(onAdjust = { adjusted++ }, onRetake = { retakes++ })) {}
        }
        compose.onNodeWithText("Ausschnitt anpassen").performScrollTo().performClick()
        compose.onNodeWithText("Neu fotografieren").performScrollTo().performClick()
        assertEquals(1, adjusted)
        assertEquals(1, retakes)
    }
}
