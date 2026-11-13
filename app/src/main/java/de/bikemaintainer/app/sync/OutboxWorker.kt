package de.bikemaintainer.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.bikemaintainer.app.data.repository.OutboxScheduler
import de.bikemaintainer.app.data.repository.OutboxSync
import de.bikemaintainer.app.data.repository.SyncResult
import java.util.concurrent.TimeUnit

/** Sends what was recorded without a connection (app#65); all logic is in [OutboxSync]. */
class OutboxWorker(
    context: Context,
    params: WorkerParameters,
    private val sync: OutboxSync,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (sync.run()) {
        SyncResult.RETRY -> Result.retry()
        // STOP: not signed in; the next login schedules it again.
        SyncResult.DONE, SyncResult.STOP -> Result.success()
    }
}

/**
 * One run as soon as there is a connection; while one runs, the next is appended (an entry added meanwhile is not
 * missed). Retries back off exponentially from 30 s.
 */
class WorkManagerOutboxScheduler(context: Context) : OutboxScheduler {
    private val context = context.applicationContext

    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val WORK_NAME = "outbox"
    }
}
