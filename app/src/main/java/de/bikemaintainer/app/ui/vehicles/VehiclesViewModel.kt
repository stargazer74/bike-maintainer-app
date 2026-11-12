package de.bikemaintainer.app.ui.vehicles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.domain.Access
import de.bikemaintainer.app.domain.AccessSource
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.VehicleSummary
import de.bikemaintainer.app.ui.common.ReloadableLoader
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
    /** Read-only accounts cannot add vehicles. */
    val access: Access = Access.FULL,
)

class VehiclesViewModel(
    vehicles: VehicleRepository,
    private val accessSource: AccessSource = AccessSource.Unrestricted,
) : ViewModel() {
    private val _state = MutableStateFlow(VehiclesUiState(access = accessSource.access.value))
    val state: StateFlow<VehiclesUiState> = _state.asStateFlow()
    private val loader = ReloadableLoader(viewModelScope) { vehicles.summaries() }

    init {
        viewModelScope.launch { accessSource.access.collect { a -> _state.update { it.copy(access = a) } } }
        viewModelScope.launch { accessSource.refreshAccess() }
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
