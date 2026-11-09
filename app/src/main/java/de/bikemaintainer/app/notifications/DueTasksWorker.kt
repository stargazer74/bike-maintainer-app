package de.bikemaintainer.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import de.bikemaintainer.app.AppContainer

/** The daily background job; all logic is in [DueTaskCheck]. */
class DueTasksWorker(
    context: Context,
    params: WorkerParameters,
    private val check: DueTaskCheck,
    private val scheduler: ReminderScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (check.run()) {
        CheckResult.DONE -> Result.success()
        CheckResult.RETRY -> Result.retry()
        CheckResult.STOP -> {
            scheduler.cancel()
            Result.success()
        }
    }
}

/** Creates [DueTasksWorker] with its dependencies from the [container]; other workers are left to the default. */
class DueTasksWorkerFactory(private val container: AppContainer) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        if (workerClassName == DueTasksWorker::class.java.name) {
            DueTasksWorker(appContext, workerParameters, container.dueTaskCheck, container.reminderScheduler)
        } else {
            null
        }
}
