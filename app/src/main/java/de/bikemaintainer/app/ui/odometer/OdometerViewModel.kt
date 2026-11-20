package de.bikemaintainer.app.ui.odometer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.domain.odometer.OdoLine
import de.bikemaintainer.app.domain.odometer.OdoReading
import de.bikemaintainer.app.domain.odometer.Point
import de.bikemaintainer.app.domain.odometer.odoPhotoArea
import de.bikemaintainer.app.domain.parseMileage
import de.bikemaintainer.app.domain.scan.GrayImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the reader made of a line: the value and the straightened line as picture (null if it could not be cut out). */
data class OdoScan(val reading: OdoReading, val strip: GrayImage?)

/**
 * Finds and reads the ODO line of an odometer photo; the real one runs [de.bikemaintainer.app.domain.odometer.OdoDetector]
 * and [de.bikemaintainer.app.domain.odometer.OdoReader] off the main thread.
 */
interface OdometerScanner {
    suspend fun detect(photo: GrayImage): OdoLine?

    suspend fun read(photo: GrayImage, corners: List<Point>, last: Int?): OdoScan
}

/** Why nothing could be read; each has its own message. */
enum class OdometerProblem { NO_LINE, FAILED }

sealed interface OdometerUiState {
    data object Camera : OdometerUiState

    data object Reading : OdometerUiState

    /**
     * The read value for checking: [photo] is the photo shrunk for the screen, [corners] the line on it, [strip] the
     * straightened line. [unsure] while the reader or the frame is not sure and the user has not corrected the value.
     */
    data class Review(
        val photo: GrayImage,
        val corners: List<Point>,
        val strip: GrayImage?,
        val text: String,
        val unsure: Boolean,
    ) : OdometerUiState {
        val value: Int? get() = parseMileage(text)
    }

    /** The user draws the frame around the line on [photo] (shrunk for the screen) – after a wrong or no line. */
    data class Adjust(val photo: GrayImage, val back: Review?) : OdometerUiState

    data class Problem(val problem: OdometerProblem, val photo: GrayImage?) : OdometerUiState

    /** Taken over; the screen hands [value] to the form once. */
    data class Done(val value: Int) : OdometerUiState
}

/**
 * Photo of the odometer → ODO line → value → check → take over (app#40). [last] is the vehicle's last known mileage
 * for the plausibility check. The photo lives only in this ViewModel's memory while the screen is open – nothing is
 * saved (also not in the SavedStateHandle) and it is dropped once the value is taken over; after process death the
 * camera opens again.
 */
class OdometerViewModel(private val last: Int?, private val scanner: OdometerScanner) : ViewModel() {
    private val _state = MutableStateFlow<OdometerUiState>(OdometerUiState.Camera)
    val state: StateFlow<OdometerUiState> = _state.asStateFlow()

    /** The 4:3 area of the photo the models read, and its shrunk copy for the screen with the factor between them. */
    private var photo: GrayImage? = null
    private var preview: GrayImage? = null
    private var factor = 1
    private var job: Job? = null

    fun onPhoto(captured: GrayImage) {
        if (_state.value != OdometerUiState.Camera) return
        _state.value = OdometerUiState.Reading
        job = viewModelScope.launch {
            _state.value = attempt(problem = { OdometerUiState.Problem(OdometerProblem.FAILED, null) }) {
                val area = odoPhotoArea(captured)
                val (small, f) = area.downscaled(PREVIEW_SIDE)
                photo = area
                preview = small
                factor = f
                val line = scanner.detect(area)
                if (line == null) {
                    OdometerUiState.Problem(OdometerProblem.NO_LINE, small)
                } else {
                    review(area, line.corners, frameUnsure = line.unsure)
                }
            }
        }
    }

    /** The camera could not be opened or the picture not taken. */
    fun onCaptureFailed() {
        _state.value = OdometerUiState.Problem(OdometerProblem.FAILED, null)
    }

    /** A corrected value counts as checked. */
    fun onText(text: String) = _state.update { s ->
        if (s is OdometerUiState.Review) s.copy(text = text.filter(Char::isDigit).take(MAX_LENGTH), unsure = false) else s
    }

    /** Draw the frame by hand: the detector found the wrong line or none. */
    fun adjust() {
        val small = preview ?: return
        val s = _state.value
        if (s is OdometerUiState.Review || s is OdometerUiState.Problem) {
            _state.value = OdometerUiState.Adjust(small, s as? OdometerUiState.Review)
        }
    }

    /** Back from drawing the frame to the previous value (or the message). */
    fun cancelAdjust() {
        val s = _state.value as? OdometerUiState.Adjust ?: return
        _state.value = s.back ?: OdometerUiState.Problem(OdometerProblem.NO_LINE, s.photo)
    }

    /** Reads the line in the frame the user drew, given as corners on the shrunk photo (clockwise from top left). */
    fun onFrame(corners: List<Point>) {
        val area = photo ?: return
        if (_state.value !is OdometerUiState.Adjust || corners.size != 4) return
        _state.value = OdometerUiState.Reading
        job = viewModelScope.launch {
            _state.value = attempt(problem = { OdometerUiState.Problem(OdometerProblem.FAILED, preview) }) {
                // Drawn by the user, so the frame itself counts as sure.
                review(area, corners.map { Point(it.x * factor, it.y * factor) }, frameUnsure = false)
            }
        }
    }

    /** Back to the camera for a new photo; the old one is dropped. */
    fun retake() {
        if (_state.value == OdometerUiState.Reading) return
        drop()
        _state.value = OdometerUiState.Camera
    }

    /** Hands the value over; the photo is dropped. */
    fun accept() {
        val review = _state.value as? OdometerUiState.Review ?: return
        val value = review.value ?: return
        drop()
        _state.value = OdometerUiState.Done(value)
    }

    private suspend fun review(area: GrayImage, corners: List<Point>, frameUnsure: Boolean): OdometerUiState {
        val scan = scanner.read(area, corners, last)
        return OdometerUiState.Review(
            photo = preview ?: area,
            corners = corners.map { Point(it.x / factor, it.y / factor) },
            strip = scan.strip,
            text = scan.reading.value?.toString().orEmpty(),
            unsure = scan.reading.unsure || frameUnsure,
        )
    }

    private suspend fun attempt(problem: () -> OdometerUiState, block: suspend () -> OdometerUiState): OdometerUiState = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        problem()
    } catch (_: OutOfMemoryError) {
        problem()
    }

    private fun drop() {
        job?.cancel()
        photo = null
        preview = null
        factor = 1
    }

    override fun onCleared() = drop()

    private companion object {
        /** Long side of the photo on the screen: enough to see the line, small enough for memory. */
        const val PREVIEW_SIDE = 1024
        const val MAX_LENGTH = 7
    }
}
