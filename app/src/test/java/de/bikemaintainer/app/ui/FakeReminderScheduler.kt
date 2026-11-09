package de.bikemaintainer.app.ui

import de.bikemaintainer.app.notifications.ReminderScheduler

/** Counts the calls of [ReminderScheduler]. */
class FakeReminderScheduler : ReminderScheduler {
    var syncCalls = 0
    var cancelCalls = 0

    override suspend fun sync() {
        syncCalls++
    }

    override fun cancel() {
        cancelCalls++
    }
}
