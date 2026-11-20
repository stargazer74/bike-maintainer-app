package de.bikemaintainer.app.ui.common

import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Snapshot
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What a screen with a reloadable list shows: no data and no error means the first load is running (spinner).
 */
data class LoadState<T>(
    /** `null` until the first successful load (or after a terminal error). */
    val data: T? = null,
    /** A visible reload (pull-to-refresh) with data on screen is in flight. */
    val refreshing: Boolean = false,
    val error: AppError? = null,
    /** Set when [data] is the stored offline state because the server could not be reached: when it was fetched. */
    val asOf: Instant? = null,
    /**
     * [data] is the state stored on the device, shown at once while the server is asked (app#69): a quiet "updating"
     * hint, no spinner over the data and no "as of" yet – that only comes if the server cannot be reached.
     */
    val updating: Boolean = false,
)

/**
 * Load / pull-to-refresh / silent reload of one screen, shared by the vehicle list and the vehicle detail.
 *
 * - [refresh]: visible (spinner or refresh indicator); a failure is shown, data on screen is kept.
 * - [reloadSilently]: no indicator; a failure is only shown if there is nothing to show yet.
 * - Only one load runs at a time. A [refresh] while a load runs makes it visible (indicator on, its failure is shown)
 *   and fetches once more after it, since the running load may have started before the change the refresh is for
 *   (e.g. a saved mileage); the result of the outdated load is dropped. A [reloadSilently] meanwhile is dropped.
 * - [onResume]: the first call only marks (the owner loaded at creation), later calls reload silently. It lives with
 *   the owner (the ViewModel of a back-stack entry), so it survives the screen's composition being dropped while
 *   another destination is shown.
 * - [isTerminal]: errors after which the old data must not stay on screen (e.g. the vehicle was deleted); they clear
 *   the data on any load, silent or not.
 * - [stored]: what is stored on the device. While nothing is on screen, a load shows it at once ([LoadState.updating])
 *   and replaces it with the answer; if the load fails, it stays with its time as [LoadState.asOf] (app#69).
 */
class ReloadableLoader<T>(
    private val scope: CoroutineScope,
    private val isTerminal: (AppError) -> Boolean = { false },
    private val stored: (suspend () -> Snapshot<T>?)? = null,
    private val fetch: suspend () -> Outcome<T>,
) {
    private val _state = MutableStateFlow(LoadState<T>())
    val state: StateFlow<LoadState<T>> = _state.asStateFlow()

    private var inFlight = false
    private var visible = false
    private var resumed = false
    private var reloadAgain = false

    fun refresh() = load(silent = false)

    fun reloadSilently() = load(silent = true)

    fun onResume() {
        if (resumed) reloadSilently() else resumed = true
    }

    private fun load(silent: Boolean) {
        if (!silent && !visible) {
            visible = true
            _state.update { if (it.data == null) it.copy(error = null) else it.copy(refreshing = true) }
        }
        if (inFlight) {
            if (!silent) reloadAgain = true
            return
        }
        inFlight = true
        scope.launch {
            try {
                val shownStored = showStored()
                val result = fetchLatest()
                _state.update { current ->
                    when (result) {
                        is Outcome.Success -> LoadState(data = result.value, asOf = result.asOf)
                        is Outcome.Failure -> {
                            // The stored state stays, now marked as such.
                            val kept = if (current.updating) current.copy(updating = false, asOf = shownStored) else current
                            when {
                                isTerminal(result.error) -> LoadState(error = result.error)
                                visible || kept.data == null -> kept.copy(refreshing = false, error = result.error)
                                else -> kept.copy(refreshing = false)
                            }
                        }
                    }
                }
            } finally {
                // Also after cancellation or an unexpected exception: a later call must work.
                inFlight = false
                visible = false
                reloadAgain = false
                _state.update { it.copy(refreshing = false, updating = false) }
            }
        }
    }

    /** Shows the stored state if nothing is on screen yet; returns its time, or null if none was shown. */
    private suspend fun showStored(): Instant? {
        if (stored == null || _state.value.data != null) return null
        val snapshot = stored.invoke() ?: return null
        var shown = false
        _state.update {
            if (it.data != null) {
                it
            } else {
                shown = true
                LoadState(data = snapshot.value, updating = true)
            }
        }
        return if (shown) snapshot.asOf else null
    }

    /** Fetches until no [refresh] arrived during the fetch; only the last result counts. */
    private suspend fun fetchLatest(): Outcome<T> {
        var result: Outcome<T>
        do {
            reloadAgain = false
            result = fetch()
        } while (reloadAgain)
        return result
    }
}
