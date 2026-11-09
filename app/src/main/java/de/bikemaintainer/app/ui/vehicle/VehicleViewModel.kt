package de.bikemaintainer.app.ui.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.data.repository.MaintenanceRepository
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.forDisplay
import de.bikemaintainer.app.domain.parseMileage
import de.bikemaintainer.app.ui.common.ReloadableLoader
import de.bikemaintainer.app.ui.common.isNotFound
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VehicleUiState(
    /** `null` until the first successful load; no vehicle and no error means the first load is running. */
    val vehicle: Vehicle? = null,
    /** Active tasks, most urgent first. */
    val tasks: List<MaintenanceTask> = emptyList(),
    /** Pull-to-refresh in flight. */
    val refreshing: Boolean = false,
    val error: AppError? = null,
    /** The "enter mileage" dialog, `null` while closed. */
    val mileageDialog: MileageDialogState? = null,
)

data class MileageDialogState(val input: String, val error: MileageError? = null, val saving: Boolean = false)

sealed interface MileageError {
    data object Invalid : MileageError

    data class TooLow(val current: Int) : MileageError

    data class Save(val error: AppError) : MileageError
}

class VehicleViewModel(
    vehicleId: Long,
    private val vehicles: VehicleRepository,
    maintenance: MaintenanceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(VehicleUiState())
    val state: StateFlow<VehicleUiState> = _state.asStateFlow()

    // A deleted vehicle is terminal: its old data must not stay on screen, also not after a silent reload.
    private val loader = ReloadableLoader(viewModelScope, isTerminal = AppError::isNotFound) {
        coroutineScope {
            val v = async { vehicles.vehicle(vehicleId) }
            val t = async { maintenance.tasks(vehicleId) }
            val vehicle = v.await()
            val tasks = t.await()
            when {
                vehicle is Outcome.Failure -> vehicle
                tasks is Outcome.Failure -> tasks
                else -> Outcome.Success(
                    (vehicle as Outcome.Success).value to (tasks as Outcome.Success).value.forDisplay(),
                )
            }
        }
    }

    init {
        viewModelScope.launch {
            loader.state.collect { l ->
                _state.update {
                    VehicleUiState(
                        vehicle = l.data?.first,
                        tasks = l.data?.second.orEmpty(),
                        refreshing = l.refreshing,
                        error = l.error,
                        // The dialog outlives silent reloads, but not the vehicle (deleted).
                        mileageDialog = if (l.data == null) null else it.mileageDialog,
                    )
                }
            }
        }
        loader.refresh()
    }

    /** Pull-to-refresh and "Try again". */
    fun refresh() = loader.refresh()

    /** Reload on return to the app: no spinner, an error only if there is nothing to show yet. */
    fun reloadSilently() = loader.reloadSilently()

    /** Every resume of the screen; the first one is skipped (loaded at creation), later ones reload silently. */
    fun onResume() = loader.onResume()

    fun openMileageDialog() {
        val vehicle = loader.state.value.data?.first ?: return
        _state.update { it.copy(mileageDialog = MileageDialogState(vehicle.currentMileage.toString())) }
    }

    fun onMileageInput(text: String) = updateDialog { it.copy(input = text, error = null) }

    fun dismissMileageDialog() {
        _state.update { if (it.mileageDialog?.saving == true) it else it.copy(mileageDialog = null) }
    }

    fun saveMileage() {
        val dialog = _state.value.mileageDialog ?: return
        if (dialog.saving) return
        val vehicle = loader.state.value.data?.first ?: return
        val km = parseMileage(dialog.input)
        when {
            km == null -> updateDialog { it.copy(error = MileageError.Invalid) }
            km < vehicle.currentMileage -> updateDialog { it.copy(error = MileageError.TooLow(vehicle.currentMileage)) }
            km == vehicle.currentMileage -> _state.update { it.copy(mileageDialog = null) }
            else -> {
                updateDialog { it.copy(saving = true, error = null) }
                viewModelScope.launch {
                    when (val result = vehicles.updateMileage(vehicle, km)) {
                        is Outcome.Success -> {
                            _state.update { it.copy(mileageDialog = null) }
                            loader.refresh()
                        }
                        is Outcome.Failure ->
                            updateDialog { it.copy(saving = false, error = MileageError.Save(result.error)) }
                    }
                }
            }
        }
    }

    private fun updateDialog(change: (MileageDialogState) -> MileageDialogState) {
        _state.update { s -> s.mileageDialog?.let { s.copy(mileageDialog = change(it)) } ?: s }
    }
}
