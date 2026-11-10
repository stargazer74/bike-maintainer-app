package de.bikemaintainer.app.ui.scan

import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.scan.GrayImage
import de.bikemaintainer.app.domain.scan.ScanOutcome
import de.bikemaintainer.app.ui.MainDispatcherRule
import de.bikemaintainer.app.ui.logmaintenance.ScanTransfer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val photo = GrayImage(4, 4)
    private val templates = setOf(MeasurementTemplate.CHAIN, MeasurementTemplate.TIRES)

    private fun viewModel(scanner: WorksheetScanner) = ScanViewModel(3, templates, scanner, decimalSeparator = ',')

    @Test
    fun readSheet_isShownForReviewWithTheDevicesDecimalSeparator() {
        val vm = viewModel { ScanOutcome.Read(chainSheet()) }
        vm.onPhoto(photo)
        val review = vm.state.value as ScanUiState.Review
        assertEquals(listOf("32,5", "", "", ""), review.fields.map { it.text })
        assertEquals(1, review.uncertainCount)
    }

    @Test
    fun correctedFieldsCountAsCheckedAndAreTakenOver() {
        val vm = viewModel { ScanOutcome.Read(chainSheet()) }
        vm.onPhoto(photo)
        vm.onValue(0, "33,5x")
        vm.onChecked(3, true)
        vm.onValue(99, "1")
        val review = vm.state.value as ScanUiState.Review
        assertEquals("33,5", review.fields[0].text)
        assertEquals(0, review.uncertainCount)
        vm.accept()
        assertEquals(
            ScanUiState.Done(
                ScanTransfer(
                    MeasurementTemplate.CHAIN,
                    values = mapOf("chain.slack" to "33,5", "chain.wearLength" to ""),
                    checks = mapOf("chain.cleaned" to true, "chain.lubricated" to true),
                ),
            ),
            vm.state.value,
        )
    }

    @Test
    fun sheetOfAnotherVehicleOrTemplateIsRefused() {
        val other = viewModel { ScanOutcome.Read(chainSheet(vehicleId = 9)) }
        other.onPhoto(photo)
        assertEquals(ScanUiState.Problem(ScanProblem.OTHER_VEHICLE), other.state.value)
        other.accept()
        assertEquals(ScanUiState.Problem(ScanProblem.OTHER_VEHICLE), other.state.value)

        val wrong = ScanViewModel(3, setOf(MeasurementTemplate.TIRES), { ScanOutcome.Read(chainSheet()) })
        wrong.onPhoto(photo)
        assertEquals(ScanUiState.Problem(ScanProblem.WRONG_TEMPLATE), wrong.state.value)
    }

    @Test
    fun problemsOfTheReaderAndRetake() {
        val outcomes = mapOf(
            ScanOutcome.NoSheet to ScanProblem.NO_SHEET,
            ScanOutcome.NoCode to ScanProblem.NO_CODE,
            ScanOutcome.UnsupportedVersion(2) to ScanProblem.NEWER_LAYOUT,
        )
        for ((outcome, problem) in outcomes) {
            val vm = viewModel { outcome }
            vm.onPhoto(photo)
            assertEquals(ScanUiState.Problem(problem), vm.state.value)
            vm.retake()
            assertEquals(ScanUiState.Camera, vm.state.value)
        }
        val crashing = viewModel { throw IllegalStateException("boom") }
        crashing.onPhoto(photo)
        assertEquals(ScanUiState.Problem(ScanProblem.FAILED), crashing.state.value)
        val noMemory = viewModel { throw OutOfMemoryError() }
        noMemory.onPhoto(photo)
        assertEquals(ScanUiState.Problem(ScanProblem.FAILED), noMemory.state.value)
        val camera = viewModel { ScanOutcome.NoSheet }
        camera.onCaptureFailed()
        assertEquals(ScanUiState.Problem(ScanProblem.FAILED), camera.state.value)
    }

    @Test
    fun onlyOnePhotoAtATime() {
        val result = CompletableDeferred<ScanOutcome>()
        var calls = 0
        val vm = viewModel { calls++; result.await() }
        vm.onPhoto(photo)
        vm.onPhoto(photo)
        assertEquals(ScanUiState.Reading, vm.state.value)
        vm.retake()
        assertEquals(ScanUiState.Reading, vm.state.value)
        result.complete(ScanOutcome.NoSheet)
        assertEquals(1, calls)
        assertTrue(vm.state.value is ScanUiState.Problem)
        assertFalse(vm.state.value is ScanUiState.Done)
    }
}
