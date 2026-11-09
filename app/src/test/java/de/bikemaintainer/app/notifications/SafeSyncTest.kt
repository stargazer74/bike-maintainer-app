package de.bikemaintainer.app.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric for android.util.Log. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SafeSyncTest {
    private class Scheduler(val failure: Throwable?) : ReminderScheduler {
        var syncs = 0

        override suspend fun sync() {
            syncs++
            failure?.let { throw it }
        }

        override fun cancel() = Unit
    }

    @Test
    fun swallowsFailures() = runTest {
        val scheduler = Scheduler(IllegalStateException("WorkManager broken"))
        scheduler.syncQuietly()
        assertEquals(1, scheduler.syncs)
    }

    @Test
    fun rethrowsCancellation() = runTest {
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { Scheduler(CancellationException("stop")).syncQuietly() }
        }
    }

    @Test
    fun passesThroughSuccess() = runTest {
        val scheduler = Scheduler(null)
        scheduler.syncQuietly()
        assertEquals(1, scheduler.syncs)
    }
}
