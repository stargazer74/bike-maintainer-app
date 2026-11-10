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
    private val templates = setOf(MeasurementTemplate.CHAIN, MeasurementTemplate.TIRES, MeasurementTemplate.VALVE_CLEARANCE)

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

    /** A scanner that returns the given outcomes one after another. */
    private fun sequence(vararg outcomes: ScanOutcome): WorksheetScanner {
        val queue = ArrayDeque(outcomes.toList())
        return WorksheetScanner { queue.removeFirst() }
    }

    @Test
    fun multiPageSheet_pagesAreCollectedAndHandedOverTogether() {
        val vm = viewModel(sequence(ScanOutcome.Read(valveSheetPage(2)), ScanOutcome.Read(valveSheetPage(1)), ScanOutcome.Read(valveSheetPage(3))))
        vm.onPhoto(photo)
        vm.accept()
        // Page 2 first: the camera opens again for page 1, nothing is handed over yet.
        assertEquals(ScanUiState.Camera, vm.state.value)
        assertEquals(SheetProgress(MeasurementTemplate.VALVE_CLEARANCE, 3, setOf(2)), vm.progress.value)
        assertEquals(1, vm.progress.value!!.nextPage)
        vm.onPhoto(photo)
        vm.accept()
        assertEquals(3, vm.progress.value!!.nextPage)
        vm.onPhoto(photo)
        vm.accept()
        val transfer = (vm.state.value as ScanUiState.Done).transfer
        assertEquals(8, transfer.cylinders)
        assertEquals(3, transfer.valvesPerCylinder)
        // All 8 cylinders × 3 valves × 3 fields from the three pages.
        assertEquals(8 * 3 * 3, transfer.values.size)
        assertEquals("0,1", transfer.values["valves.8.EXHAUST.1.measured"])
        assertEquals("0,1", transfer.values["valves.1.INTAKE.1.measured"])
    }

    @Test
    fun multiPageSheet_canBeFinishedEarly() {
        val vm = viewModel(sequence(ScanOutcome.Read(valveSheetPage(1))))
        vm.finish()
        assertEquals(ScanUiState.Camera, vm.state.value) // nothing taken over yet: nothing to finish
        vm.onPhoto(photo)
        vm.accept()
        vm.finish()
        val transfer = (vm.state.value as ScanUiState.Done).transfer
        assertEquals(3 * 3 * 3, transfer.values.size) // only page 1: cylinders 1–3
    }

    @Test
    fun multiPageSheet_pageOfAnotherSheetIsRefusedAndCollectedPagesStay() {
        val vm = viewModel(
            sequence(
                ScanOutcome.Read(valveSheetPage(1)),
                ScanOutcome.Read(valveSheetPage(2, cylinders = 6)),
                ScanOutcome.Read(valveSheetPage(1)),
            ),
        )
        vm.onPhoto(photo)
        vm.accept()
        vm.onPhoto(photo)
        assertEquals(ScanUiState.Problem(ScanProblem.OTHER_SHEET), vm.state.value)
        vm.retake()
        assertEquals(setOf(1), vm.progress.value!!.done)
        // The same page again replaces its values.
        vm.onPhoto(photo)
        vm.onValue(0, "0,2")
        vm.accept()
        assertEquals(setOf(1), vm.progress.value!!.done)
        vm.finish()
        assertEquals("0,2", (vm.state.value as ScanUiState.Done).transfer.values["valves.1.INTAKE.1.measured"])
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
