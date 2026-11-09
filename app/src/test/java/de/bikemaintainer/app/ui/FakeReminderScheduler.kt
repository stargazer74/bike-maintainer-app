package de.bikemaintainer.app.ui

import de.bikemaintainer.app.notifications.ReminderScheduler

/** Counts the calls of [ReminderScheduler]. */
class FakeReminderScheduler : ReminderScheduler {
    var syncCalls = 0
    var cancelCalls = 0
    var failWith: Exception? = null

    override suspend fun sync() {
        syncCalls++
        failWith?.let { throw it }
    }

    override fun cancel() {
        cancelCalls++
        failWith?.let { throw it }
    }
}
