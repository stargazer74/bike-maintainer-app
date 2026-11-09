package de.bikemaintainer.app.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.bikemaintainer.app.data.storage.SettingsStore
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Plans or cancels the daily due task check. */
interface ReminderScheduler {
    /** Schedules the check if reminders are wanted (switch, permission, login), otherwise cancels it. */
    suspend fun sync()

    fun cancel()
}

class WorkManagerReminderScheduler(
    context: Context,
    private val settings: SettingsStore,
    private val notificationsAllowed: () -> Boolean,
) : ReminderScheduler {
    private val context = context.applicationContext

    override suspend fun sync() {
        if (!remindersWanted(settings, notificationsAllowed)) {
            cancel()
            return
        }
        val request = PeriodicWorkRequestBuilder<DueTasksWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayUntilNextMorning(ZonedDateTime.now()))
            .build()
        // KEEP: an existing schedule (and its time of day) stays as it is.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "due_tasks"
    }
}

/** Time from [now] until the next [hour] o'clock local time: today if still before, else tomorrow (DST-aware). */
fun delayUntilNextMorning(now: ZonedDateTime, hour: Int = 7): Duration {
    val today = now.toLocalDate().atTime(hour, 0).atZone(now.zone)
    val next = if (now.isBefore(today)) today else now.toLocalDate().plusDays(1).atTime(hour, 0).atZone(now.zone)
    return Duration.between(now, next)
}
