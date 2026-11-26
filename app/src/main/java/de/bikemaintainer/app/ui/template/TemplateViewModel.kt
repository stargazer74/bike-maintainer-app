package de.bikemaintainer.app.ui.template

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.data.repository.MaintenanceRepository
import de.bikemaintainer.app.data.repository.TemplateRepository
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.domain.AccessSource
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TemplateApplyResult
import de.bikemaintainer.app.domain.TemplateSearchParams
import de.bikemaintainer.app.domain.TemplateSummary
import de.bikemaintainer.app.domain.TemplateTaskRow
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleTemplate
import de.bikemaintainer.app.domain.fillEmpty
import de.bikemaintainer.app.domain.initialRows
import de.bikemaintainer.app.domain.isSubscriptionRequired
import de.bikemaintainer.app.domain.toApplyRequest
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What went wrong after the screen was loaded; shown above the buttons, the screen stays usable. */
sealed interface TemplateProblem {
    data object Search : TemplateProblem

    data object Load : TemplateProblem

    data class Apply(val error: AppError) : TemplateProblem
}

/** Why a "last done" value cannot be sent: the server answers 400 for it. */
enum class LastDoneError { KM_TOO_HIGH, DATE_OUT_OF_RANGE }

data class TemplateUiState(
    /** `null` while the vehicle is loading (or failed to load, see [loadError]). */
    val vehicle: Vehicle? = null,
    val loadError: AppError? = null,
    /** The ViewModel's date source, the latest date "last done" may have. */
    val today: LocalDate = LocalDate.MIN,
    val make: String = "",
    val model: String = "",
    val searching: Boolean = false,
    /** `null` until a search has been answered. */
    val results: List<TemplateSummary>? = null,
    /** A template chosen from the search is being loaded. */
    val loadingTemplate: Boolean = false,
    /** The chosen template; the apply step is shown once it is set. */
    val template: VehicleTemplate? = null,
    val rows: List<TemplateTaskRow> = emptyList(),
    /** The disclaimer was read; never stored, `false` every time the apply step opens. */
    val confirmed: Boolean = false,
    val applying: Boolean = false,
    val problem: TemplateProblem? = null,
    /** The template was applied; the screen leaves once and tells the vehicle detail. */
    val applied: TemplateApplyResult? = null,
) {
    /** "Last done" can only be given for a vehicle that has been driven. */
    val showLastDone: Boolean get() = (vehicle?.currentMileage ?: 0) > 0
    val canSearch: Boolean get() = make.trim().length >= MIN_MAKE_LENGTH && !searching
    val selectedCount: Int get() = rows.count { it.selected && !it.existing }
    val canApply: Boolean
        get() = confirmed && !applying && applied == null && selectedCount > 0 &&
            rows.none { it.selected && !it.existing && lastDoneError(it) != null }

    /** The first problem of the row's "last done" values; `null` if they fit the vehicle (or are not given). */
    fun lastDoneError(row: TemplateTaskRow): LastDoneError? {
        val v = vehicle ?: return null
        val km = row.lastDoneKm
        val date = row.lastDoneDate
        return when {
            km != null && km > v.currentMileage -> LastDoneError.KM_TOO_HIGH
            date != null && (date.isBefore(v.firstRegistrationDate) || date.isAfter(today)) -> LastDoneError.DATE_OUT_OF_RANGE
            else -> null
        }
    }

    companion object {
        const val MIN_MAKE_LENGTH = 2
    }
}

/**
 * Applies a maintenance plan template to a vehicle (Epic backend#150): search, choose, pick the tasks, confirm the
 * disclaimer. Everything runs online; nothing is queued and nothing is stored.
 */
class TemplateViewModel(
    private val vehicleId: Long,
    private val vehicles: VehicleRepository,
    private val maintenance: MaintenanceRepository,
    private val templates: TemplateRepository,
    private val accessSource: AccessSource = AccessSource.Unrestricted,
    private val clock: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    private val _state = MutableStateFlow(TemplateUiState())
    val state: StateFlow<TemplateUiState> = _state.asStateFlow()

    /** `templateKey`s of the tasks the vehicle has from a template already. */
    private var existingKeys: Set<String> = emptySet()

    init {
        load()
    }

    fun retryLoad() {
        if (_state.value.vehicle == null) load()
    }

    private fun load() {
        _state.update { it.copy(loadError = null) }
        viewModelScope.launch {
            val vehicle = when (val result = vehicles.vehicle(vehicleId)) {
                is Outcome.Success -> result.value
                is Outcome.Failure -> return@launch _state.update { it.copy(loadError = result.error) }
            }
            val tasks = when (val result = maintenance.tasks(vehicleId)) {
                is Outcome.Success -> result.value
                is Outcome.Failure -> return@launch _state.update { it.copy(loadError = result.error) }
            }
            existingKeys = tasks.mapNotNull { it.templateKey }.toSet()
            _state.update { it.copy(vehicle = vehicle, today = clock(), make = vehicle.make.orEmpty(), model = vehicle.model.orEmpty()) }
            if (_state.value.canSearch) search()
        }
    }

    fun onMake(value: String) = _state.update { it.copy(make = value) }

    fun onModel(value: String) = _state.update { it.copy(model = value) }

    /** Type and model year are those of the vehicle; only make and model can be changed. */
    fun search() {
        val s = _state.value
        val vehicle = s.vehicle ?: return
        if (!s.canSearch || s.loadingTemplate) return
        _state.update { it.copy(searching = true, problem = null) }
        viewModelScope.launch {
            val params = TemplateSearchParams(s.make.trim(), vehicle.type, s.model.trim(), vehicle.modelYear)
            when (val result = templates.search(params)) {
                is Outcome.Success -> {
                    _state.update { it.copy(searching = false, results = result.value) }
                    result.value.singleOrNull()?.let(::pick)
                }
                is Outcome.Failure -> _state.update { it.copy(searching = false, problem = TemplateProblem.Search) }
            }
        }
    }

    fun pick(summary: TemplateSummary) {
        val vehicle = _state.value.vehicle ?: return
        if (_state.value.loadingTemplate) return
        _state.update { it.copy(loadingTemplate = true, problem = null) }
        viewModelScope.launch {
            when (val result = templates.get(summary.id)) {
                is Outcome.Success -> _state.update {
                    it.copy(
                        loadingTemplate = false,
                        template = result.value,
                        rows = initialRows(result.value, existingKeys, vehicle, clock()),
                        confirmed = false,
                    )
                }
                is Outcome.Failure -> _state.update { it.copy(loadingTemplate = false, problem = TemplateProblem.Load) }
            }
        }
    }

    fun backToSearch() = _state.update {
        if (it.applying) it else it.copy(template = null, rows = emptyList(), confirmed = false, problem = null)
    }

    fun toggle(index: Int) = updateRow(index) { if (it.existing) it else it.copy(selected = !it.selected) }

    fun onLastDoneKm(index: Int, text: String) {
        val km = text.filter(Char::isDigit).take(MAX_KM_DIGITS).toIntOrNull()
        updateRow(index) { it.copy(lastDoneKm = km) }
    }

    fun onLastDoneDate(index: Int, date: LocalDate?) = updateRow(index) { it.copy(lastDoneDate = date) }

    /** Empty "last done" fields get the vehicle's mileage and today. */
    fun fillEmptyFields() = _state.update { s ->
        val mileage = s.vehicle?.currentMileage ?: return@update s
        if (s.applying) s else s.copy(rows = fillEmpty(s.rows, mileage, clock()))
    }

    fun onConfirmed(confirmed: Boolean) = _state.update { if (it.applying) it else it.copy(confirmed = confirmed) }

    fun apply() {
        val s = _state.value
        val template = s.template ?: return
        if (!s.canApply) return
        _state.update { it.copy(applying = true, problem = null) }
        viewModelScope.launch {
            when (val result = templates.apply(vehicleId, toApplyRequest(template.id, s.rows))) {
                is Outcome.Success -> _state.update { it.copy(applied = result.value) }
                is Outcome.Failure -> {
                    if (result.error.isSubscriptionRequired()) accessSource.subscriptionRequired()
                    _state.update { it.copy(applying = false, problem = TemplateProblem.Apply(result.error)) }
                }
            }
        }
    }

    private fun updateRow(index: Int, change: (TemplateTaskRow) -> TemplateTaskRow) = _state.update { s ->
        if (s.applying || index !in s.rows.indices) s else s.copy(rows = s.rows.mapIndexed { i, row -> if (i == index) change(row) else row })
    }

    private companion object {
        /** More digits do not fit an Int mileage. */
        const val MAX_KM_DIGITS = 7
    }
}
