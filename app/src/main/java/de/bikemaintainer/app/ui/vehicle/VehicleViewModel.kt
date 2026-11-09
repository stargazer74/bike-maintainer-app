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
import de.bikemaintainer.app.ui.common.ReloadableLoader
import de.bikemaintainer.app.ui.common.isNotFound
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VehicleUiState(
    /** `null` until the first successful load; no vehicle and no error means the first load is running. */
    val vehicle: Vehicle? = null,
    /** Active tasks, most urgent first. */
    val tasks: List<MaintenanceTask> = emptyList(),
    /** Pull-to-refresh in flight. */
    val refreshing: Boolean = false,
    val error: AppError? = null,
)

class VehicleViewModel(
    vehicleId: Long,
    vehicles: VehicleRepository,
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
                _state.value = VehicleUiState(
                    vehicle = l.data?.first,
                    tasks = l.data?.second.orEmpty(),
                    refreshing = l.refreshing,
                    error = l.error,
                )
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
}
