package de.bikemaintainer.app.ui.vehicles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.VehicleSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VehiclesUiState(
    /** `null` until the first successful load. */
    val summaries: List<VehicleSummary>? = null,
    /** First load (or retry without data) in flight. */
    val loading: Boolean = true,
    /** Pull-to-refresh in flight. */
    val refreshing: Boolean = false,
    val error: AppError? = null,
    /** The server address, offered as link when there are no vehicles. */
    val webAppUrl: String? = null,
)

class VehiclesViewModel(
    private val vehicles: VehicleRepository,
    settings: Flow<StoredSettings>,
) : ViewModel() {
    private val _state = MutableStateFlow(VehiclesUiState())
    val state: StateFlow<VehiclesUiState> = _state.asStateFlow()
    private var inFlight = false

    init {
        viewModelScope.launch { settings.collect { s -> _state.update { it.copy(webAppUrl = s.serverUrl) } } }
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
                if (it.summaries == null) it.copy(loading = true, error = null) else it.copy(refreshing = true)
            }
        }
        viewModelScope.launch {
            try {
                val result = vehicles.summaries()
                _state.update { current ->
                    when (result) {
                        is Outcome.Success ->
                            current.copy(summaries = result.value, loading = false, refreshing = false, error = null)
                        is Outcome.Failure -> when {
                            current.summaries == null ->
                                current.copy(loading = false, refreshing = false, error = result.error)
                            silent -> current.copy(loading = false, refreshing = false)
                            else -> current.copy(loading = false, refreshing = false, error = result.error)
                        }
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
