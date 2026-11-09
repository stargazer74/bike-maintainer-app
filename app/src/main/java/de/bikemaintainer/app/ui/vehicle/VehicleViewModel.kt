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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VehicleUiState(
    /** `null` until the first successful load. */
    val vehicle: Vehicle? = null,
    /** Active tasks, most urgent first. */
    val tasks: List<MaintenanceTask> = emptyList(),
    /** First load (or retry without data) in flight. */
    val loading: Boolean = true,
    /** Pull-to-refresh in flight. */
    val refreshing: Boolean = false,
    val error: AppError? = null,
)

class VehicleViewModel(
    private val vehicleId: Long,
    private val vehicles: VehicleRepository,
    private val maintenance: MaintenanceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(VehicleUiState())
    val state: StateFlow<VehicleUiState> = _state.asStateFlow()
    private var inFlight = false

    init {
        load(silent = false)
    }

    /** Pull-to-refresh and "Try again". */
    fun refresh() = load(silent = false)

    /** Reload on return to the app: no spinner, an error only if there is nothing to show yet. */
    fun reloadSilently() = load(silent = true)

    private fun load(silent: Boolean) {
        if (inFlight) return
        inFlight = true
        if (!silent) {
            _state.update {
                if (it.vehicle == null) it.copy(loading = true, error = null) else it.copy(refreshing = true)
            }
        }
        viewModelScope.launch {
            try {
                val (vehicle, tasks) = coroutineScope {
                    val v = async { vehicles.vehicle(vehicleId) }
                    val t = async { maintenance.tasks(vehicleId) }
                    v.await() to t.await()
                }
                val failure = (vehicle as? Outcome.Failure)?.error ?: (tasks as? Outcome.Failure)?.error
                _state.update { current ->
                    when {
                        failure == null && vehicle is Outcome.Success && tasks is Outcome.Success ->
                            current.copy(
                                vehicle = vehicle.value,
                                tasks = tasks.value.forDisplay(),
                                loading = false,
                                refreshing = false,
                                error = null,
                            )
                        current.vehicle == null || !silent ->
                            current.copy(loading = false, refreshing = false, error = failure)
                        else -> current.copy(loading = false, refreshing = false)
                    }
                }
            } finally {
                // Never stay stuck (e.g. cancellation or an unexpected exception): a later call must work.
                inFlight = false
                _state.update { it.copy(loading = false, refreshing = false) }
            }
        }
    }
}
