package de.bikemaintainer.app.ui.common

import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReloadableLoaderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var result: Outcome<String> = Outcome.Success("a")
    private var gate: CompletableDeferred<Unit>? = null
    private var calls = 0

    private fun loader(isTerminal: (AppError) -> Boolean = { false }) =
        ReloadableLoader(scope, isTerminal) {
            calls++
            gate?.await()
            result
        }

    @After fun tearDown() = scope.cancel()

    @Test fun nothingIsLoadedBeforeTheFirstCall() {
        val l = loader()
        assertEquals(LoadState<String>(), l.state.value)
        assertEquals(0, calls)
    }

    @Test fun refreshLoadsData() {
        val l = loader()
        l.refresh()
        assertEquals(LoadState(data = "a"), l.state.value)
    }

    @Test fun firstLoadFailureShowsErrorAndRetryClearsItWhileLoading() {
        result = Outcome.Failure(AppError.Network)
        val l = loader()
        l.refresh()
        assertEquals(LoadState<String>(error = AppError.Network), l.state.value)
        gate = CompletableDeferred()
        l.refresh()
        assertEquals(LoadState<String>(), l.state.value) // spinner again (no data, no error)
        result = Outcome.Success("b")
        gate!!.complete(Unit)
        assertEquals(LoadState(data = "b"), l.state.value)
    }

    @Test fun offlineDataCarriesItsTimeUntilAFreshLoad() {
        val asOf = java.time.Instant.parse("2026-11-13T20:40:00Z")
        result = Outcome.Success("old", asOf)
        val l = loader()
        l.refresh()
        assertEquals(LoadState(data = "old", asOf = asOf), l.state.value)
        result = Outcome.Success("new")
        l.reloadSilently()
        assertEquals(LoadState(data = "new"), l.state.value)
    }

    @Test fun refreshWithDataShowsRefreshingAndKeepsDataOnFailure() {
        val l = loader()
        l.refresh()
        gate = CompletableDeferred()
        result = Outcome.Failure(AppError.Network)
        l.refresh()
        assertTrue(l.state.value.refreshing)
        gate!!.complete(Unit)
        assertEquals(LoadState(data = "a", error = AppError.Network), l.state.value)
    }

    @Test fun silentReloadHidesFailureWhenThereIsData() {
        val l = loader()
        l.refresh()
        gate = CompletableDeferred()
        result = Outcome.Failure(AppError.Network)
        l.reloadSilently()
        assertFalse(l.state.value.refreshing)
        gate!!.complete(Unit)
        assertEquals(LoadState(data = "a"), l.state.value)
    }

    @Test fun silentReloadShowsFailureWithoutData() {
        result = Outcome.Failure(AppError.Network)
        val l = loader()
        l.reloadSilently()
        assertEquals(LoadState<String>(error = AppError.Network), l.state.value)
    }

    @Test fun successClearsError() {
        val l = loader()
        l.refresh()
        result = Outcome.Failure(AppError.Network)
        l.refresh()
        result = Outcome.Success("b")
        l.reloadSilently()
        assertEquals(LoadState(data = "b"), l.state.value)
    }

    @Test fun concurrentCallsDoNotStartASecondLoad() {
        gate = CompletableDeferred()
        val l = loader()
        l.refresh()
        l.refresh()
        l.reloadSilently()
        assertEquals(1, calls)
        gate!!.complete(Unit)
        assertEquals(2, calls) // the refresh during the load runs once more afterwards, not twice
        l.refresh()
        assertEquals(3, calls)
    }

    @Test fun refreshWhileLoadingFetchesOnceMoreWithTheNewData() {
        val first = CompletableDeferred<Unit>()
        var n = 0
        val l = ReloadableLoader(scope) {
            n++
            if (n == 1) first.await()
            Outcome.Success("v$n")
        }
        l.reloadSilently()
        l.refresh() // e.g. after saving: the running load may have started before the change
        assertEquals(1, n)
        first.complete(Unit)
        assertEquals(2, n)
        assertEquals(LoadState(data = "v2"), l.state.value)
    }

    @Test fun silentReloadWhileLoadingIsNotRepeated() {
        gate = CompletableDeferred()
        val l = loader()
        l.refresh()
        l.reloadSilently()
        gate!!.complete(Unit)
        assertEquals(1, calls)
        assertEquals(LoadState(data = "a"), l.state.value)
    }

    @Test fun refreshDuringSilentReloadMakesItVisible() {
        val l = loader()
        l.refresh()
        gate = CompletableDeferred()
        result = Outcome.Failure(AppError.Network)
        l.reloadSilently()
        l.refresh()
        assertEquals(2, calls) // no second request: the running one is upgraded
        assertTrue(l.state.value.refreshing)
        gate!!.complete(Unit)
        assertEquals(LoadState(data = "a", error = AppError.Network), l.state.value)
    }

    @Test fun refreshDuringSilentFirstLoadClearsErrorAndShowsItsFailure() {
        result = Outcome.Failure(AppError.Network)
        val l = loader()
        l.refresh()
        gate = CompletableDeferred()
        result = Outcome.Failure(AppError.Http(503, null))
        l.reloadSilently()
        assertEquals(AppError.Network, l.state.value.error) // silent: the old message stays until the result
        l.refresh()
        assertNull(l.state.value.error)
        gate!!.complete(Unit)
        assertEquals(AppError.Http(503, null), l.state.value.error)
    }

    @Test fun terminalErrorDropsDataOnRefreshAndSilentReload() {
        for (silent in listOf(false, true)) {
            result = Outcome.Success("a")
            val l = loader(isTerminal = { it == AppError.NotFound })
            l.refresh()
            result = Outcome.Failure(AppError.NotFound)
            if (silent) l.reloadSilently() else l.refresh()
            assertEquals(LoadState<String>(error = AppError.NotFound), l.state.value)
        }
    }

    @Test fun firstResumeOnlyMarksLaterResumesReloadSilently() {
        val l = loader()
        l.refresh()
        l.onResume()
        assertEquals(1, calls)
        gate = CompletableDeferred()
        l.onResume()
        assertEquals(2, calls)
        assertFalse(l.state.value.refreshing)
        gate!!.complete(Unit)
        l.onResume()
        assertEquals(3, calls)
    }

    @Test fun crashingLoadDoesNotBlockLaterCalls() {
        val crashing = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> })
        var crash = true
        val l = ReloadableLoader(crashing) {
            calls++
            if (crash) error("boom") else Outcome.Success("a")
        }
        l.refresh()
        crash = false
        l.refresh()
        assertEquals(2, calls)
        assertEquals(LoadState(data = "a"), l.state.value)
        crashing.cancel()
    }
}
