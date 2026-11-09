package de.bikemaintainer.app.ui.common

import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
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
 */
class ReloadableLoader<T>(
    private val scope: CoroutineScope,
    private val isTerminal: (AppError) -> Boolean = { false },
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
                val result = fetchLatest()
                _state.update { current ->
                    when (result) {
                        is Outcome.Success -> LoadState(data = result.value)
                        is Outcome.Failure -> when {
                            isTerminal(result.error) -> LoadState(error = result.error)
                            visible || current.data == null -> current.copy(refreshing = false, error = result.error)
                            else -> current.copy(refreshing = false)
                        }
                    }
                }
            } finally {
                // Also after cancellation or an unexpected exception: a later call must work.
                inFlight = false
                visible = false
                reloadAgain = false
                _state.update { it.copy(refreshing = false) }
            }
        }
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
