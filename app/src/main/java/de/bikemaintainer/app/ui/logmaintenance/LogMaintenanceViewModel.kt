package de.bikemaintainer.app.ui.logmaintenance

import androidx.lifecycle.SavedStateHandle
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
import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.ShimSuggestion
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.forDisplay
import de.bikemaintainer.app.domain.isSubscriptionRequired
import de.bikemaintainer.app.domain.parseMileage
import de.bikemaintainer.app.ui.common.isNotFound
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

const val MAX_NOTES_LENGTH = 1000

const val SHIM_DEBOUNCE_MILLIS = 400L

/** Shim suggestions for the valve rows (same order), or why there are none. */
data class ShimState(
    val suggestions: List<ShimSuggestion>? = null,
    val error: AppError? = null,
    val calculating: Boolean = false,
)

/** How a logged maintenance ended; the vehicle screen shows the matching message. */
enum class LogResult { SAVED, SAVED_MILEAGE_FAILED }

sealed interface LogError {
    data object InvalidMileage : LogError

    data object NoTask : LogError

    /** A measured value is invalid; the fields show what is wrong. */
    data object Measurements : LogError

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
    /** Templates, target values and last measurements; `null` until loaded. The form works without them. */
    val measurementContext: MeasurementContext? = null,
    val measurementLoadError: AppError? = null,
    val measurements: MeasurementDrafts = MeasurementDrafts(),
    /** Field problems are shown once the user tried to save. */
    val showMeasurementErrors: Boolean = false,
    val shim: ShimState = ShimState(),
    val done: LogResult? = null,
    /** The log is saved; the form stays locked until the screen is gone (also while [done] has been consumed). */
    val finished: Boolean = false,
    /** Scanning needs [Access.canScan]; worksheets of the trial carry a demo code. */
    val access: Access = Access.FULL,
) {
    /** No edits and no save: a save is running or the log is already saved. */
    val locked: Boolean get() = saving || finished

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
    /** Keeps the form (and a finished save) across process death; only what the user can see or typed, never logged. */
    private val savedState: SavedStateHandle,
    private val measurementRepository: MeasurementRepository,
    private val today: () -> LocalDate = LocalDate::now,
    /** Waits this long after the last change of a valve row before asking the backend for shim suggestions. */
    private val shimDebounceMillis: Long = SHIM_DEBOUNCE_MILLIS,
    private val decimalSeparator: Char = DecimalFormatSymbols.getInstance().decimalSeparator,
    private val accessSource: AccessSource = AccessSource.Unrestricted,
) : ViewModel() {
    /** The latest selectable day. */
    val latestDate: LocalDate get() = today()

    private val _state = MutableStateFlow(
        LogMaintenanceUiState(date = today()).let { initial ->
            val result = savedState.get<String>(KEY_RESULT)?.let { name -> LogResult.entries.firstOrNull { it.name == name } }
            // Restored after the log was saved: hand the result on again instead of offering a second save.
            if (result == null) initial else initial.copy(done = result, finished = true)
        },
    )
    val state: StateFlow<LogMaintenanceUiState> = _state.asStateFlow()

    private var loading = false
    private var loadingMeasurements = false
    private var shimJob: Job? = null

    init {
        viewModelScope.launch { accessSource.access.collect { a -> _state.update { it.copy(access = a) } } }
        retryLoad()
        retryMeasurements()
    }

    /** Loads what the measurements need; a failure only hides adding new templates, the rest of the form works. */
    fun retryMeasurements() {
        if (loadingMeasurements || _state.value.measurementContext != null || _state.value.finished) return
        loadingMeasurements = true
        _state.update { it.copy(measurementLoadError = null) }
        viewModelScope.launch {
            try {
                when (val result = measurementRepository.context(vehicleId)) {
                    is Outcome.Failure -> _state.update { it.copy(measurementLoadError = result.error) }
                    is Outcome.Success -> {
                        _state.update { it.copy(measurementContext = result.value) }
                        scheduleShims()
                    }
                }
            } finally {
                loadingMeasurements = false
            }
        }
    }

    fun retryLoad() {
        if (loading || _state.value.vehicle != null || _state.value.loadError?.isNotFound() == true) return
        loading = true
        _state.update { it.copy(loadError = null) }
        viewModelScope.launch {
            try {
                when (val result = fetchForm()) {
                    is Outcome.Failure -> _state.update { it.copy(loadError = result.error) }
                    is Outcome.Success -> {
                        val (vehicle, tasks) = result.value
                        val ids = tasks.map { it.id }.toSet()
                        _state.update {
                            if (savedState.contains(KEY_SELECTED)) {
                                // Restored: the user's form wins over the prefill; deleted tasks drop out.
                                it.copy(
                                    vehicle = vehicle,
                                    tasks = tasks,
                                    selected = savedState.get<LongArray>(KEY_SELECTED)?.toSet().orEmpty() intersect ids,
                                    mileageInput = savedState.get<String>(KEY_MILEAGE).orEmpty(),
                                    date = savedState.get<Long>(KEY_DATE)?.let(LocalDate::ofEpochDay) ?: today(),
                                    notes = savedState.get<String>(KEY_NOTES).orEmpty(),
                                    raiseMileage = savedState.get<Boolean>(KEY_RAISE) ?: true,
                                    measurements = restoreMeasurements(),
                                )
                            } else {
                                it.copy(
                                    vehicle = vehicle,
                                    tasks = tasks,
                                    selected = tasks.filter { t -> t.status.isDue() }.map { t -> t.id }.toSet(),
                                    mileageInput = vehicle.currentMileage.toString(),
                                    date = today(),
                                )
                            }
                        }
                        persist()
                        scheduleShims()
                    }
                }
            } finally {
                loading = false
            }
        }
    }

    /** Called once the screen has handed [LogMaintenanceUiState.done] on, so a recreated screen does not report it again. */
    fun consumeDone() = _state.update { it.copy(done = null) }

    fun onDate(date: LocalDate) = edit { it.copy(date = date) }

    fun onMileageInput(text: String) = edit { it.copy(mileageInput = text) }

    fun toggleTask(id: Long) = edit { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }

    fun onNotes(text: String) = edit { it.copy(notes = text.capNotes()) }

    fun onRaiseMileage(raise: Boolean) = edit { it.copy(raiseMileage = raise) }

    /** Adds a template that fits the vehicle; valve rows and chain links are prefilled from the vehicle. */
    fun addTemplate(template: MeasurementTemplate) {
        val context = _state.value.measurementContext ?: return
        if (template !in context.templates) return
        editMeasurements { drafts ->
            when (template) {
                MeasurementTemplate.VALVE_CLEARANCE -> drafts.copy(
                    valves = newValveDrafts(context.specification, context.latest.measurements.valves, decimalSeparator),
                )
                MeasurementTemplate.BRAKES -> drafts.copy(brakes = BrakeDraft())
                MeasurementTemplate.CHAIN -> drafts.copy(chain = newChainDraft(context.specification))
                MeasurementTemplate.TIRES -> drafts.copy(tires = TireDraft())
            }
        }
    }

    fun removeTemplate(template: MeasurementTemplate) = editMeasurements { it.without(template) }

    /**
     * Takes over the checked values of a photographed worksheet page (Epic #120). Like adding by hand, only templates
     * that fit the vehicle; the scan screen only offers the photo once the context is loaded.
     */
    fun applyScan(scan: ScanTransfer) {
        val context = _state.value.measurementContext ?: return
        if (scan.template !in context.templates) return
        editMeasurements { it.withScan(scan, context.specification, context.latest.measurements.valves, decimalSeparator) }
    }

    fun changeLayout(cylinders: Int, valvesPerCylinder: Int) = editMeasurements { drafts ->
        drafts.valves?.let { drafts.copy(valves = valveLayout(cylinders, valvesPerCylinder, it)) } ?: drafts
    }

    fun onValve(index: Int, valve: ValveDraft) = editMeasurements { drafts ->
        drafts.copy(valves = drafts.valves?.mapIndexed { i, old -> if (i == index) valve else old })
    }

    /** Takes the suggested shim of a row over as its new shim; it stays editable. */
    fun applyShim(index: Int) {
        val s = _state.value
        val shim = s.shim.suggestions?.getOrNull(index)?.suggestedShim ?: return
        val valve = s.measurements.valves?.getOrNull(index) ?: return
        onValve(index, valve.copy(shimNew = shim.toInput(decimalSeparator)))
    }

    fun onBrakes(brakes: BrakeDraft) = editMeasurements { it.copy(brakes = it.brakes?.let { brakes }) }

    fun onChain(chain: ChainDraft) = editMeasurements { it.copy(chain = it.chain?.let { chain }) }

    fun onTires(tires: TireDraft) = editMeasurements { it.copy(tires = it.tires?.let { tires }) }

    /** A measurement edit; a change of a measured value or an old shim asks for new shim suggestions. */
    private fun editMeasurements(change: (MeasurementDrafts) -> MeasurementDrafts) {
        val before = _state.value.measurements.valves?.shimInputs()
        edit { it.copy(measurements = change(it.measurements)) }
        if (_state.value.measurements.valves?.shimInputs() != before || _state.value.measurements.valves == null) {
            scheduleShims()
        }
    }

    /**
     * Asks the backend for shim suggestions once typing pauses; a newer change cancels the pending request. Without
     * the target values or a complete row there is nothing to ask. A failure (e.g. no connection) keeps the input.
     */
    private fun scheduleShims() {
        shimJob?.cancel()
        val s = _state.value
        val inputs = s.measurements.valves?.shimInputs()
        if (inputs == null || s.measurementContext?.specification?.canSuggestShims != true) {
            _state.update { it.copy(shim = ShimState()) }
            return
        }
        shimJob = viewModelScope.launch {
            delay(shimDebounceMillis)
            _state.update { it.copy(shim = it.shim.copy(calculating = true)) }
            when (val result = measurementRepository.calculateShims(vehicleId, inputs)) {
                is Outcome.Success -> _state.update { it.copy(shim = ShimState(suggestions = result.value)) }
                is Outcome.Failure -> _state.update { it.copy(shim = ShimState(error = result.error)) }
            }
        }
    }

    fun save() {
        val s = _state.value
        val vehicle = s.vehicle ?: return
        if (s.locked) return
        val km = parseMileage(s.mileageInput)
        when {
            km == null -> _state.update { it.copy(error = LogError.InvalidMileage) }
            s.selected.isEmpty() -> _state.update { it.copy(error = LogError.NoTask) }
            s.measurements.firstError() != null ->
                _state.update { it.copy(error = LogError.Measurements, showMeasurementErrors = true) }
            else -> {
                _state.update { it.copy(saving = true, error = null) }
                val raise = s.offerRaise && s.raiseMileage
                viewModelScope.launch {
                    val result = maintenance.logMaintenance(
                        vehicleId,
                        s.date,
                        km,
                        s.selected,
                        s.notes.ifBlank { null },
                        s.measurements.toMeasurements().takeUnless { it.isEmpty },
                    )
                    when (result) {
                        is Outcome.Failure -> onSaveFailed(result.error)
                        is Outcome.Success -> {
                            // The log exists now; a failing raise must never lead to sending it again.
                            val done = if (raise && vehicles.updateMileage(vehicle, km) is Outcome.Failure) {
                                LogResult.SAVED_MILEAGE_FAILED
                            } else {
                                LogResult.SAVED
                            }
                            savedState[KEY_RESULT] = done.name
                            shimJob?.cancel()
                            _state.update { it.copy(saving = false, finished = true, done = done) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun onSaveFailed(error: AppError) {
        if (error.isSubscriptionRequired()) accessSource.subscriptionRequired()
        when {
            // Deleted meanwhile: the same terminal state as when loading (message and Back, no form).
            error.isNotFound() -> showNotFound()
            // Rejected input is often a task deleted in the web app meanwhile: reload the tasks (still locked), keep
            // the input and drop selected tasks that no longer exist.
            error is AppError.Http && error.status == 400 -> {
                when (val reload = fetchForm()) {
                    is Outcome.Failure -> if (reload.error.isNotFound()) {
                        showNotFound()
                        return
                    }
                    is Outcome.Success -> {
                        val (vehicle, tasks) = reload.value
                        val ids = tasks.map { it.id }.toSet()
                        _state.update { it.copy(vehicle = vehicle, tasks = tasks, selected = it.selected intersect ids) }
                        persist()
                    }
                }
                _state.update { it.copy(saving = false, error = LogError.Save(error)) }
            }
            else -> _state.update { it.copy(saving = false, error = LogError.Save(error)) }
        }
    }

    private fun showNotFound() = _state.update {
        it.copy(vehicle = null, tasks = emptyList(), loadError = AppError.NotFound, saving = false, error = null)
    }

    private suspend fun fetchForm(): Outcome<Pair<Vehicle, List<MaintenanceTask>>> = coroutineScope {
        val v = async { vehicles.vehicle(vehicleId) }
        val t = async { maintenance.tasks(vehicleId) }
        val vehicle = v.await()
        val tasks = t.await()
        when {
            vehicle is Outcome.Failure -> vehicle
            tasks is Outcome.Failure -> tasks
            else -> Outcome.Success((vehicle as Outcome.Success).value to (tasks as Outcome.Success).value.forDisplay())
        }
    }

    private fun TaskStatus.isDue() = this == TaskStatus.OVERDUE || this == TaskStatus.DUE_SOON

    /** A form edit; ignored while saving and after the log was saved, and it clears the last error. */
    private fun edit(change: (LogMaintenanceUiState) -> LogMaintenanceUiState) {
        if (_state.value.locked || _state.value.vehicle == null) return
        _state.update { if (it.locked) it else change(it).copy(error = null) }
        persist()
    }

    private fun persist() {
        val s = _state.value
        savedState[KEY_SELECTED] = s.selected.toLongArray()
        savedState[KEY_MILEAGE] = s.mileageInput
        savedState[KEY_DATE] = s.date.toEpochDay()
        savedState[KEY_NOTES] = s.notes
        savedState[KEY_RAISE] = s.raiseMileage
        savedState[KEY_MEASUREMENTS] = Json.encodeToString(s.measurements)
    }

    private fun restoreMeasurements(): MeasurementDrafts {
        val json = savedState.get<String>(KEY_MEASUREMENTS) ?: return MeasurementDrafts()
        return try {
            Json.decodeFromString<MeasurementDrafts>(json)
        } catch (_: SerializationException) {
            MeasurementDrafts()
        } catch (_: IllegalArgumentException) {
            MeasurementDrafts()
        }
    }

    private companion object {
        const val KEY_SELECTED = "log_selected"
        const val KEY_MILEAGE = "log_mileage"
        const val KEY_DATE = "log_date"
        const val KEY_NOTES = "log_notes"
        const val KEY_RAISE = "log_raise"
        const val KEY_RESULT = "log_done"
        const val KEY_MEASUREMENTS = "log_measurements"
    }
}

/** At most [MAX_NOTES_LENGTH] chars, without cutting an emoji (surrogate pair) in half. */
private fun String.capNotes(): String {
    if (length <= MAX_NOTES_LENGTH) return this
    val end = if (this[MAX_NOTES_LENGTH - 1].isHighSurrogate()) MAX_NOTES_LENGTH - 1 else MAX_NOTES_LENGTH
    return substring(0, end)
}
