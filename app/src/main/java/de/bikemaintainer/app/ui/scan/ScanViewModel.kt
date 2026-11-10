package de.bikemaintainer.app.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.scan.GrayImage
import de.bikemaintainer.app.domain.scan.ScanLayout
import de.bikemaintainer.app.domain.scan.ScanOutcome
import de.bikemaintainer.app.domain.scan.SheetCode
import de.bikemaintainer.app.ui.logmaintenance.ScanTransfer
import java.text.DecimalFormatSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Reads a photo of a worksheet; the real one runs [de.bikemaintainer.app.domain.scan.SheetReader] off the main thread. */
fun interface WorksheetScanner {
    suspend fun read(photo: GrayImage): ScanOutcome
}

/** Why nothing can be taken over; each has its own message. */
enum class ScanProblem { NO_SHEET, NO_CODE, NEWER_LAYOUT, OTHER_VEHICLE, WRONG_TEMPLATE, FAILED }

/** One field in the review: [text] as the user would type it (decimal separator of the device). */
data class ReviewField(
    val field: ScanLayout.Field,
    val text: String,
    val checked: Boolean,
    val uncertain: Boolean,
    val crop: GrayImage,
)

sealed interface ScanUiState {
    /** Camera preview, waiting for the photo. */
    data object Camera : ScanUiState

    data object Reading : ScanUiState

    data class Review(val code: SheetCode, val fields: List<ReviewField>) : ScanUiState {
        val uncertainCount: Int get() = fields.count { it.uncertain }
    }

    data class Problem(val problem: ScanProblem) : ScanUiState

    /** The user took the values over; the screen hands [transfer] to the log form once. */
    data class Done(val transfer: ScanTransfer) : ScanUiState
}

/**
 * Photo → recognised values → review → take over (Epic #120). The photo and the field crops only live in this
 * ViewModel's memory: nothing is saved (also not in the SavedStateHandle) – after process death the camera opens again.
 */
class ScanViewModel(
    private val vehicleId: Long,
    private val templates: Set<MeasurementTemplate>,
    private val scanner: WorksheetScanner,
    private val decimalSeparator: Char = DecimalFormatSymbols.getInstance().decimalSeparator,
) : ViewModel() {
    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Camera)
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    fun onPhoto(photo: GrayImage) {
        if (_state.value != ScanUiState.Camera) return
        _state.value = ScanUiState.Reading
        viewModelScope.launch {
            val outcome = try {
                scanner.read(photo)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            _state.value = review(outcome)
        }
    }

    /** The camera could not be opened or the picture not taken. */
    fun onCaptureFailed() {
        _state.value = ScanUiState.Problem(ScanProblem.FAILED)
    }

    fun onValue(index: Int, text: String) = editField(index) { it.copy(text = text.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(MAX_LENGTH)) }

    fun onChecked(index: Int, checked: Boolean) = editField(index) { it.copy(checked = checked) }

    /** Back to the camera, e.g. for a sharper photo or the next page; the current values are dropped. */
    fun retake() {
        if (_state.value != ScanUiState.Reading) _state.value = ScanUiState.Camera
    }

    fun accept() {
        val review = _state.value as? ScanUiState.Review ?: return
        val code = review.code
        _state.value = ScanUiState.Done(
            ScanTransfer(
                template = code.template,
                cylinders = code.cylinders,
                valvesPerCylinder = code.valvesPerCylinder,
                values = review.fields.filter { it.field.kind == ScanLayout.FieldKind.DIGITS }.associate { it.field.id to it.text.trim() },
                checks = review.fields.filter { it.field.kind == ScanLayout.FieldKind.CHECKBOX }.associate { it.field.id to it.checked },
            ),
        )
    }

    /** A corrected field counts as checked by the user. */
    private fun editField(index: Int, change: (ReviewField) -> ReviewField) = _state.update { s ->
        if (s !is ScanUiState.Review || index !in s.fields.indices) {
            s
        } else {
            s.copy(fields = s.fields.mapIndexed { i, f -> if (i == index) change(f).copy(uncertain = false) else f })
        }
    }

    private fun review(outcome: ScanOutcome?): ScanUiState = when (outcome) {
        null -> ScanUiState.Problem(ScanProblem.FAILED)
        ScanOutcome.NoSheet -> ScanUiState.Problem(ScanProblem.NO_SHEET)
        ScanOutcome.NoCode -> ScanUiState.Problem(ScanProblem.NO_CODE)
        is ScanOutcome.UnsupportedVersion -> ScanUiState.Problem(ScanProblem.NEWER_LAYOUT)
        is ScanOutcome.Read -> {
            val code = outcome.sheet.code
            when {
                // Another vehicle's sheet: nothing at all is taken over, not even partly.
                code.vehicleId != vehicleId -> ScanUiState.Problem(ScanProblem.OTHER_VEHICLE)
                code.template !in templates -> ScanUiState.Problem(ScanProblem.WRONG_TEMPLATE)
                else -> ScanUiState.Review(
                    code,
                    outcome.sheet.readings.map {
                        ReviewField(it.field, it.value.replace('.', decimalSeparator), it.checked, it.uncertain, it.crop)
                    },
                )
            }
        }
    }

    private companion object {
        const val MAX_LENGTH = 10
    }
}
