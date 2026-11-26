package de.bikemaintainer.app.ui.vehicleform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.data.repository.TemplateRepository
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.domain.AccessSource
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TemplateSearchParams
import de.bikemaintainer.app.domain.VehicleDraft
import de.bikemaintainer.app.domain.VehicleInput
import de.bikemaintainer.app.domain.isSubscriptionRequired
import de.bikemaintainer.app.domain.templateSearchParams
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VehicleFormUiState(
    val editing: Boolean,
    /** `null` while an existing vehicle is loading (or failed to load, see [loadError]). */
    val draft: VehicleDraft? = null,
    val loadError: AppError? = null,
    /** Field problems are shown once the user tried to save. */
    val showErrors: Boolean = false,
    val saving: Boolean = false,
    val saveError: AppError? = null,
    /** Id of the saved vehicle; the screen leaves once. */
    val saved: Long? = null,
    /** Read-only account editing a vehicle: the data is only shown, deleting stays possible (Epic backend#136). */
    val readOnly: Boolean = false,
    /** A template exists for the entered data of a new vehicle (purely informational, never blocks saving). */
    val templateHint: Boolean = false,
    /** With [saved]: the template screen opens next because a template exists for the new vehicle. */
    val offerTemplate: Boolean = false,
)

/**
 * Creates a vehicle ([vehicleId] `null`) or edits one; the same fields and checks as the web app. With [templates], a
 * new vehicle shows a hint as soon as a maintenance plan template exists for the entered data (Epic backend#150); a
 * failing search only means no hint, saving always stays possible.
 */
class VehicleFormViewModel(
    private val vehicleId: Long?,
    private val vehicles: VehicleRepository,
    private val accessSource: AccessSource = AccessSource.Unrestricted,
    private val templates: TemplateRepository? = null,
    private val clock: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    private val _state = MutableStateFlow(
        VehicleFormUiState(editing = vehicleId != null, draft = if (vehicleId == null) VehicleDraft.empty(clock()) else null),
    )
    val state: StateFlow<VehicleFormUiState> = _state.asStateFlow()

    /** Today; the model year may be at most one year later. */
    val today: LocalDate get() = clock()

    init {
        if (vehicleId != null) {
            load(vehicleId)
            viewModelScope.launch {
                accessSource.access.collect { a -> _state.update { it.copy(readOnly = !a.canWrite) } }
            }
        }
    }

    fun retryLoad() {
        if (vehicleId != null && _state.value.draft == null) load(vehicleId)
    }

    private fun load(id: Long) {
        _state.update { it.copy(loadError = null) }
        viewModelScope.launch {
            when (val result = vehicles.vehicle(id)) {
                is Outcome.Success -> _state.update { it.copy(draft = VehicleDraft.of(result.value)) }
                is Outcome.Failure -> _state.update { it.copy(loadError = result.error) }
            }
        }
    }

    fun edit(change: (VehicleDraft) -> VehicleDraft) {
        _state.update { s ->
            if (s.saving || s.saved != null || s.draft == null || s.readOnly) s else s.copy(draft = change(s.draft), saveError = null)
        }
        _state.value.draft?.let(::watchForTemplate)
    }

    private suspend fun templateExistsFor(input: VehicleInput): Boolean {
        val repository = templates ?: return false
        val params = templateSearchParams(input.make, input.model, input.type, input.modelYear) ?: return false
        return (repository.search(params) as? Outcome.Success)?.value?.isNotEmpty() == true
    }

    private var hintJob: Job? = null
    private var hintParams: TemplateSearchParams? = null

    /** Asks for a template once make and model are filled in; only for a new vehicle, and only when the input changed. */
    private fun watchForTemplate(draft: VehicleDraft) {
        val repository = templates ?: return
        if (vehicleId != null || _state.value.saved != null) return
        val year = draft.modelYear.trim().takeIf { it.length == YEAR_DIGITS }?.toIntOrNull()
        val params = templateSearchParams(draft.make, draft.model, draft.type, year)
        if (params == hintParams) return
        hintParams = params
        hintJob?.cancel()
        // The old answer belongs to other input: no hint until the new search says so.
        _state.update { it.copy(templateHint = false) }
        if (params == null) {
            _state.update { it.copy(templateHint = false) }
            return
        }
        hintJob = viewModelScope.launch {
            delay(HINT_DEBOUNCE_MILLIS)
            val found = (repository.search(params) as? Outcome.Success)?.value?.isNotEmpty() == true
            _state.update { it.copy(templateHint = found) }
        }
    }

    fun save() {
        val s = _state.value
        if (s.saving || s.saved != null || s.readOnly) return
        val input = s.draft?.toInput(clock())
        if (input == null) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        _state.update { it.copy(saving = true, saveError = null, showErrors = true) }
        viewModelScope.launch {
            val result = if (vehicleId == null) vehicles.createVehicle(input) else vehicles.updateVehicle(vehicleId, input)
            when (result) {
                is Outcome.Success -> {
                    // The offer is decided by a fresh search for what was saved, not by the typing-time hint.
                    hintJob?.cancel()
                    val offer = vehicleId == null && templateExistsFor(input)
                    _state.update { it.copy(saving = false, saved = result.value.id, offerTemplate = offer) }
                }
                is Outcome.Failure -> {
                    if (result.error.isSubscriptionRequired()) accessSource.subscriptionRequired()
                    _state.update { it.copy(saving = false, saveError = result.error) }
                }
            }
        }
    }

    private companion object {
        const val HINT_DEBOUNCE_MILLIS = 400L
        const val YEAR_DIGITS = 4
    }
}
