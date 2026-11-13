package de.bikemaintainer.app.ui.vehicles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.data.repository.PendingRepository
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.domain.Access
import de.bikemaintainer.app.domain.AccessSource
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.PendingItem
import de.bikemaintainer.app.domain.PendingState
import de.bikemaintainer.app.domain.VehicleSummary
import de.bikemaintainer.app.ui.common.ReloadableLoader
import java.time.Instant
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
    /** Set while the list is the one stored offline (no connection): when it was loaded. */
    val asOf: Instant? = null,
    /** Read-only accounts cannot add vehicles. */
    val access: Access = Access.FULL,
    /** Recordings not sent yet (app#65). */
    val pending: List<PendingItem> = emptyList(),
) {
    /** Entries that still go out by themselves. */
    val pendingWaiting: Int get() = pending.count { it.state == PendingState.Waiting && it.vehicleId in vehicleIds }

    /** Entries of listed vehicles that wait for the user's decision (shown on the vehicle). */
    val pendingAttention: Int get() = pending.count { it.state != PendingState.Waiting && it.vehicleId in vehicleIds }

    /**
     * Entries of vehicles not in the (fresh) list, e.g. recorded before someone else signed in: they can never be
     * sent and are only reachable here. Unknown while the list is the stored offline one.
     */
    val pendingOrphans: List<String>
        get() = if (summaries == null || asOf != null) emptyList() else pending.filter { it.vehicleId !in vehicleIds }.map { it.id }

    private val vehicleIds: Set<Long> get() = summaries.orEmpty().map { it.vehicle.id }.toSet()
}

class VehiclesViewModel(
    vehicles: VehicleRepository,
    private val accessSource: AccessSource = AccessSource.Unrestricted,
    private val pending: PendingRepository = PendingRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(VehiclesUiState(access = accessSource.access.value))
    val state: StateFlow<VehiclesUiState> = _state.asStateFlow()
    private val loader = ReloadableLoader(viewModelScope) { vehicles.summaries() }

    init {
        viewModelScope.launch { accessSource.access.collect { a -> _state.update { it.copy(access = a) } } }
        viewModelScope.launch { accessSource.refreshAccess() }
        viewModelScope.launch {
            var before = 0
            pending.items.collect { list ->
                _state.update { it.copy(pending = list) }
                // Something was sent meanwhile: the statuses may have changed.
                if (list.size < before) loader.reloadSilently()
                before = list.size
            }
        }
        viewModelScope.launch {
            loader.state.collect { l ->
                _state.update { it.copy(summaries = l.data, refreshing = l.refreshing, error = l.error, asOf = l.asOf) }
            }
        }
        loader.refresh()
    }

    /** Drops the entries that belong to none of the listed vehicles. */
    fun discardOrphans() {
        val ids = _state.value.pendingOrphans
        viewModelScope.launch { ids.forEach { pending.discard(it) } }
    }

    /** Pull-to-refresh and "Try again". */
    fun refresh() = loader.refresh()

    /** Reload on return to the app: no spinner, an error only if there is nothing to show yet. */
    fun reloadSilently() = loader.reloadSilently()

    /** Every resume of the screen; the first one is skipped (loaded at creation), later ones reload silently. */
    fun onResume() = loader.onResume()
}
