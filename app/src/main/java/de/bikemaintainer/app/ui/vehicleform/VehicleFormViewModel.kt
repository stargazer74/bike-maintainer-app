package de.bikemaintainer.app.ui.vehicleform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.domain.AccessSource
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.VehicleDraft
import de.bikemaintainer.app.domain.VehicleField
import de.bikemaintainer.app.domain.isSubscriptionRequired
import java.time.LocalDate
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
)

/** Creates a vehicle ([vehicleId] `null`) or edits one; the same fields and checks as the web app. */
class VehicleFormViewModel(
    private val vehicleId: Long?,
    private val vehicles: VehicleRepository,
    private val accessSource: AccessSource = AccessSource.Unrestricted,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    private val _state = MutableStateFlow(
        VehicleFormUiState(editing = vehicleId != null, draft = if (vehicleId == null) VehicleDraft.empty(today()) else null),
    )
    val state: StateFlow<VehicleFormUiState> = _state.asStateFlow()

    /** The latest selectable day of first registration. */
    val latestDate: LocalDate get() = today()

    init {
        if (vehicleId != null) load(vehicleId)
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

    /** Problems of the current input, shown after the first save attempt. */
    fun invalidFields(): Set<VehicleField> {
        val s = _state.value
        return if (s.showErrors) s.draft?.invalidFields(today()).orEmpty() else emptySet()
    }

    fun edit(change: (VehicleDraft) -> VehicleDraft) = _state.update { s ->
        if (s.saving || s.saved != null || s.draft == null) s else s.copy(draft = change(s.draft), saveError = null)
    }

    fun save() {
        val s = _state.value
        if (s.saving || s.saved != null) return
        val input = s.draft?.toInput(today())
        if (input == null) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        _state.update { it.copy(saving = true, saveError = null, showErrors = true) }
        viewModelScope.launch {
            val result = if (vehicleId == null) vehicles.createVehicle(input) else vehicles.updateVehicle(vehicleId, input)
            when (result) {
                is Outcome.Success -> _state.update { it.copy(saving = false, saved = result.value.id) }
                is Outcome.Failure -> {
                    if (result.error.isSubscriptionRequired()) accessSource.subscriptionRequired()
                    _state.update { it.copy(saving = false, saveError = result.error) }
                }
            }
        }
    }
}
