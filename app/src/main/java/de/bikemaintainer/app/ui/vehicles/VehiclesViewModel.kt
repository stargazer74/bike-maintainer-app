package de.bikemaintainer.app.ui.vehicles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.VehicleSummary
import de.bikemaintainer.app.ui.common.ReloadableLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VehiclesUiState(
    /** `null` until the first successful load; no summaries and no error means the first load is running. */
    val summaries: List<VehicleSummary>? = null,
    /** Pull-to-refresh in flight. */
    val refreshing: Boolean = false,
    val error: AppError? = null,
    /** The server address, offered as link when there are no vehicles. */
    val webAppUrl: String? = null,
)

class VehiclesViewModel(
    vehicles: VehicleRepository,
    settings: Flow<StoredSettings>,
) : ViewModel() {
    private val _state = MutableStateFlow(VehiclesUiState())
    val state: StateFlow<VehiclesUiState> = _state.asStateFlow()
    private val loader = ReloadableLoader(viewModelScope) { vehicles.summaries() }

    init {
        viewModelScope.launch { settings.collect { s -> _state.update { it.copy(webAppUrl = s.serverUrl) } } }
        viewModelScope.launch {
            loader.state.collect { l ->
                _state.update { it.copy(summaries = l.data, refreshing = l.refreshing, error = l.error) }
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
