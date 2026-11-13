package de.bikemaintainer.app.ui.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.data.repository.MaintenanceRepository
import de.bikemaintainer.app.data.repository.MeasurementRepository
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.domain.Access
import de.bikemaintainer.app.domain.AccessSource
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.MeasurementContext
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.forDisplay
import de.bikemaintainer.app.domain.inactiveForDisplay
import de.bikemaintainer.app.domain.isSubscriptionRequired
import de.bikemaintainer.app.domain.olderOf
import de.bikemaintainer.app.domain.parseMileage
import de.bikemaintainer.app.ui.common.ReloadableLoader
import de.bikemaintainer.app.ui.common.isNotFound
import java.time.Instant
import kotlinx.coroutines.Job
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
    /** Switched-off tasks by name; they can be edited and switched on again. */
    val inactiveTasks: List<MaintenanceTask> = emptyList(),
    /** Pull-to-refresh in flight. */
    val refreshing: Boolean = false,
    val error: AppError? = null,
    /** Set while vehicle and tasks are the ones stored offline (no connection): when they were loaded. */
    val asOf: Instant? = null,
    /** The "enter mileage" dialog, `null` while closed. */
    val mileageDialog: MileageDialogState? = null,
    /** Target values and last measurements; `null` until loaded. A failure only hides them, the screen works on. */
    val measurements: MeasurementContext? = null,
    /** Read-only accounts ([Access.canWrite] false) cannot enter the mileage or log maintenance. */
    val access: Access = Access.FULL,
    val deleting: Boolean = false,
    val deleteError: AppError? = null,
    /** The vehicle is deleted; the screen leaves once. */
    val deleted: Boolean = false,
) {
    /** Mileage, logs and tasks can be recorded: write access and no delete running. */
    val canRecord: Boolean get() = access.canWrite && !deleting && !deleted
}

data class MileageDialogState(val input: String, val error: MileageError? = null, val saving: Boolean = false)

sealed interface MileageError {
    data object Invalid : MileageError

    data class TooLow(val current: Int) : MileageError

    data class Save(val error: AppError) : MileageError
}

class VehicleViewModel(
    private val vehicleId: Long,
    private val vehicles: VehicleRepository,
    maintenance: MaintenanceRepository,
    private val measurementRepository: MeasurementRepository,
    private val accessSource: AccessSource = AccessSource.Unrestricted,
    /** Removes the due notification of the deleted vehicle; it would otherwise open a vehicle that is gone. */
    private val cancelNotification: (Long) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(VehicleUiState(access = accessSource.access.value))
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
                    (vehicle as Outcome.Success).value to (tasks as Outcome.Success).value,
                    olderOf(vehicle.asOf, tasks.asOf),
                )
            }
        }
    }

    init {
        viewModelScope.launch {
            var loaded: Any? = null
            loader.state.collect { l ->
                // Every successful (re)load also refreshes the measurements, e.g. after a log was saved.
                if (l.data != null && l.data !== loaded) loadMeasurements()
                loaded = l.data
                _state.update {
                    VehicleUiState(
                        vehicle = l.data?.first,
                        tasks = l.data?.second.orEmpty().forDisplay(),
                        inactiveTasks = l.data?.second.orEmpty().inactiveForDisplay(),
                        refreshing = l.refreshing,
                        error = l.error,
                        asOf = l.asOf,
                        // The dialog outlives silent reloads, but not the vehicle (deleted).
                        mileageDialog = if (l.data == null) null else it.mileageDialog,
                        measurements = if (l.data == null) null else it.measurements,
                        access = it.access,
                        deleting = it.deleting,
                        deleteError = it.deleteError,
                        deleted = it.deleted,
                    )
                }
            }
        }
        viewModelScope.launch { accessSource.access.collect { a -> _state.update { it.copy(access = a) } } }
        viewModelScope.launch { accessSource.refreshAccess() }
        loader.refresh()
    }

    private var measurementJob: Job? = null

    private fun loadMeasurements() {
        measurementJob?.cancel()
        measurementJob = viewModelScope.launch {
            val result = measurementRepository.context(vehicleId)
            if (result is Outcome.Success) {
                _state.update { if (it.vehicle == null) it else it.copy(measurements = result.value) }
            }
        }
    }

    /** Pull-to-refresh and "Try again". */
    fun refresh() = loader.refresh()

    /** Reload on return to the app: no spinner, an error only if there is nothing to show yet. */
    fun reloadSilently() = loader.reloadSilently()

    /** Every resume of the screen; the first one is skipped (loaded at creation), later ones reload silently. */
    fun onResume() = loader.onResume()

    fun openMileageDialog() {
        if (!_state.value.canRecord) return
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
                        is Outcome.Failure -> if (result.error.isNotFound()) {
                            // Deleted meanwhile: close the dialog; the reload turns the detail into its terminal
                            // not-found state.
                            _state.update { it.copy(mileageDialog = null) }
                            loader.refresh()
                        } else {
                            if (result.error.isSubscriptionRequired()) accessSource.subscriptionRequired()
                            updateDialog { it.copy(saving = false, error = MileageError.Save(result.error)) }
                        }
                    }
                }
            }
        }
    }

    /** Deletes the vehicle with everything recorded for it; also possible on a read-only account. */
    fun deleteVehicle() {
        if (_state.value.deleting || _state.value.deleted) return
        _state.update { it.copy(deleting = true, deleteError = null) }
        viewModelScope.launch {
            when (val result = vehicles.deleteVehicle(vehicleId)) {
                is Outcome.Success -> onDeleted()
                // Already gone counts as deleted.
                is Outcome.Failure -> if (result.error.isNotFound()) {
                    onDeleted()
                } else {
                    _state.update { it.copy(deleting = false, deleteError = result.error) }
                }
            }
        }
    }

    private fun onDeleted() {
        cancelNotification(vehicleId)
        _state.update { it.copy(deleting = false, deleted = true) }
    }

    private fun updateDialog(change: (MileageDialogState) -> MileageDialogState) {
        _state.update { s -> s.mileageDialog?.let { s.copy(mileageDialog = change(it)) } ?: s }
    }
}
