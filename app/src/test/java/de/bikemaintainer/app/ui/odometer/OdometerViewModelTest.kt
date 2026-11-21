package de.bikemaintainer.app.ui.odometer

import de.bikemaintainer.app.domain.odometer.OdoReading
import de.bikemaintainer.app.domain.odometer.Point
import de.bikemaintainer.app.domain.scan.GrayImage
import de.bikemaintainer.app.ui.MainDispatcherRule
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OdometerViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    /** A portrait photo as the camera takes it: the models get the 4:3 band in the middle. */
    private val photo = GrayImage(1500, 2000)

    @Test
    fun thePhotoIsReadAgainstTheLastMileageAndShownForChecking() {
        val scanner = FakeOdometerScanner()
        val vm = OdometerViewModel(12_000, scanner)
        vm.onPhoto(photo)
        val review = vm.state.value as OdometerUiState.Review
        assertEquals(1500 to 1125, scanner.photos.single().let { it.width to it.height })
        assertEquals(12_000, scanner.reads.single().second)
        assertEquals("12400", review.text)
        assertFalse(review.unsure)
        // Shown smaller (factor 2): the frame is drawn on the small photo.
        assertEquals(750, review.photo.width)
        assertEquals(Point(50.0, 60.0), review.corners[0])
    }

    @Test
    fun anUnsureValueOrFrameIsMarkedUntilTheUserCorrectsIt() {
        val vm = OdometerViewModel(null, FakeOdometerScanner(reading = OdoReading(1234, true)))
        vm.onPhoto(photo)
        assertTrue((vm.state.value as OdometerUiState.Review).unsure)
        vm.onText("12 345a")
        val review = vm.state.value as OdometerUiState.Review
        assertEquals("12345", review.text)
        assertFalse(review.unsure)

        val frame = FakeOdometerScanner()
        frame.line = frame.line!!.copy(confidence = 0.5)
        val unsureFrame = OdometerViewModel(12_000, frame)
        unsureFrame.onPhoto(photo)
        assertTrue((unsureFrame.state.value as OdometerUiState.Review).unsure)
    }

    @Test
    fun takingOverHandsTheValueOnOnlyIfItIsAMileage() {
        val vm = OdometerViewModel(12_000, FakeOdometerScanner(reading = OdoReading(null, true)))
        vm.onPhoto(photo)
        assertNull((vm.state.value as OdometerUiState.Review).value)
        vm.accept()
        assertTrue(vm.state.value is OdometerUiState.Review)
        vm.onText("12500")
        vm.accept()
        assertEquals(OdometerUiState.Done(12_500), vm.state.value)
        // The photo is gone: nothing left to adjust.
        vm.adjust()
        assertEquals(OdometerUiState.Done(12_500), vm.state.value)
    }

    @Test
    fun withoutALineTheUserDrawsTheFrameThemselves() {
        val scanner = FakeOdometerScanner(line = null)
        val vm = OdometerViewModel(12_000, scanner)
        vm.onPhoto(photo)
        val problem = vm.state.value as OdometerUiState.Problem
        assertEquals(OdometerProblem.NO_LINE, problem.problem)
        vm.adjust()
        assertTrue(vm.state.value is OdometerUiState.Adjust)
        vm.cancelAdjust()
        assertEquals(problem, vm.state.value)
        vm.adjust()
        vm.onFrame(listOf(Point(10.0, 20.0), Point(110.0, 20.0), Point(110.0, 50.0), Point(10.0, 50.0)))
        // On the full photo (factor 2); a frame drawn by the user is not marked as unsure.
        assertEquals(listOf(Point(20.0, 40.0), Point(220.0, 40.0), Point(220.0, 100.0), Point(20.0, 100.0)), scanner.reads.single().first)
        val review = vm.state.value as OdometerUiState.Review
        assertEquals("12400", review.text)
        assertFalse(review.unsure)
        assertEquals(Point(10.0, 20.0), review.corners[0])
    }

    @Test
    fun aWrongLineCanBeReplacedAndTheOldValueComesBackOnCancel() {
        val vm = OdometerViewModel(12_000, FakeOdometerScanner())
        vm.onPhoto(photo)
        val review = vm.state.value as OdometerUiState.Review
        vm.adjust()
        assertEquals(review, (vm.state.value as OdometerUiState.Adjust).back)
        vm.cancelAdjust()
        assertEquals(review, vm.state.value)
    }

    @Test
    fun failuresOfferANewPhoto() {
        val scanner = FakeOdometerScanner(failure = IOException("model missing"))
        val vm = OdometerViewModel(12_000, scanner)
        vm.onPhoto(photo)
        assertEquals(OdometerUiState.Problem(OdometerProblem.FAILED, null), vm.state.value)
        scanner.failure = null
        vm.retake()
        assertEquals(OdometerUiState.Camera, vm.state.value)
        vm.onPhoto(photo)
        assertTrue(vm.state.value is OdometerUiState.Review)

        val camera = OdometerViewModel(12_000, FakeOdometerScanner())
        camera.onCaptureFailed()
        assertEquals(OdometerUiState.Problem(OdometerProblem.FAILED, null), camera.state.value)
    }

    @Test
    fun aSecondPhotoWhileReadingIsIgnored() {
        val scanner = FakeOdometerScanner()
        val vm = OdometerViewModel(12_000, scanner)
        vm.onPhoto(photo)
        vm.onPhoto(photo)
        assertEquals(1, scanner.photos.size)
    }

    @Test
    fun aRetakeDropsTheReviewedValue() {
        val vm = OdometerViewModel(12_000, FakeOdometerScanner(line = null))
        vm.onPhoto(photo)
        vm.retake()
        assertEquals(OdometerUiState.Camera, vm.state.value)
        vm.adjust()
        assertEquals(OdometerUiState.Camera, vm.state.value)
    }
}
