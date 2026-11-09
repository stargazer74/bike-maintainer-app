package de.bikemaintainer.app.ui.logmaintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.data.repository.MaintenanceRepository
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.forDisplay
import de.bikemaintainer.app.domain.parseMileage
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_NOTES_LENGTH = 1000

/** How a logged maintenance ended; the vehicle screen shows the matching message. */
enum class LogResult { SAVED, SAVED_MILEAGE_FAILED }

sealed interface LogError {
    data object InvalidMileage : LogError

    data object NoTask : LogError

    data class Save(val error: AppError) : LogError
}

data class LogMaintenanceUiState(
    /** `null` until the first successful load; the form is initialised from it exactly once. */
    val vehicle: Vehicle? = null,
    val tasks: List<MaintenanceTask> = emptyList(),
    val loadError: AppError? = null,
    val date: LocalDate,
    val mileageInput: String = "",
    val selected: Set<Long> = emptySet(),
    val notes: String = "",
    val raiseMileage: Boolean = true,
    val saving: Boolean = false,
    val error: LogError? = null,
    val done: LogResult? = null,
) {
    /** A valid mileage above the vehicle's current one can be written back to the vehicle. */
    val offerRaise: Boolean
        get() {
            val current = vehicle?.currentMileage ?: return false
            val km = parseMileage(mileageInput) ?: return false
            return km > current
        }
}

class LogMaintenanceViewModel(
    private val vehicleId: Long,
    private val vehicles: VehicleRepository,
    private val maintenance: MaintenanceRepository,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    /** The latest selectable day. */
    val latestDate: LocalDate get() = today()

    private val _state = MutableStateFlow(LogMaintenanceUiState(date = today()))
    val state: StateFlow<LogMaintenanceUiState> = _state.asStateFlow()

    private var loading = false

    init {
        retryLoad()
    }

    fun retryLoad() {
        if (loading || _state.value.vehicle != null) return
        loading = true
        _state.update { it.copy(loadError = null) }
        viewModelScope.launch {
            try {
                val result = coroutineScope {
                    val v = async { vehicles.vehicle(vehicleId) }
                    val t = async { maintenance.tasks(vehicleId) }
                    val vehicle = v.await()
                    val tasks = t.await()
                    when {
                        vehicle is Outcome.Failure -> vehicle
                        tasks is Outcome.Failure -> tasks
                        else -> Outcome.Success((vehicle as Outcome.Success).value to (tasks as Outcome.Success).value)
                    }
                }
                when (result) {
                    is Outcome.Failure -> _state.update { it.copy(loadError = result.error) }
                    is Outcome.Success -> {
                        val (vehicle, all) = result.value
                        val tasks = all.forDisplay()
                        _state.update {
                            it.copy(
                                vehicle = vehicle,
                                tasks = tasks,
                                selected = tasks.filter { t -> t.status.isDue() }.map { t -> t.id }.toSet(),
                                mileageInput = vehicle.currentMileage.toString(),
                                date = today(),
                            )
                        }
                    }
                }
            } finally {
                loading = false
            }
        }
    }

    fun onDate(date: LocalDate) = edit { it.copy(date = date) }

    fun onMileageInput(text: String) = edit { it.copy(mileageInput = text) }

    fun toggleTask(id: Long) = edit { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }

    fun onNotes(text: String) = edit { it.copy(notes = text.take(MAX_NOTES_LENGTH)) }

    fun onRaiseMileage(raise: Boolean) = edit { it.copy(raiseMileage = raise) }

    fun save() {
        val s = _state.value
        val vehicle = s.vehicle ?: return
        if (s.saving) return
        val km = parseMileage(s.mileageInput)
        when {
            km == null -> _state.update { it.copy(error = LogError.InvalidMileage) }
            s.selected.isEmpty() -> _state.update { it.copy(error = LogError.NoTask) }
            else -> {
                _state.update { it.copy(saving = true, error = null) }
                val raise = s.offerRaise && s.raiseMileage
                viewModelScope.launch {
                    when (val result = maintenance.logMaintenance(vehicleId, s.date, km, s.selected, s.notes.ifBlank { null })) {
                        is Outcome.Failure ->
                            _state.update { it.copy(saving = false, error = LogError.Save(result.error)) }
                        is Outcome.Success -> {
                            // The log exists now; a failing raise must never lead to sending it again.
                            val done = if (raise && vehicles.updateMileage(vehicle, km) is Outcome.Failure) {
                                LogResult.SAVED_MILEAGE_FAILED
                            } else {
                                LogResult.SAVED
                            }
                            _state.update { it.copy(saving = false, done = done) }
                        }
                    }
                }
            }
        }
    }

    private fun TaskStatus.isDue() = this == TaskStatus.OVERDUE || this == TaskStatus.DUE_SOON

    /** A form edit; ignored while saving, and it clears the last error. */
    private fun edit(change: (LogMaintenanceUiState) -> LogMaintenanceUiState) {
        _state.update { if (it.saving) it else change(it).copy(error = null) }
    }
}
