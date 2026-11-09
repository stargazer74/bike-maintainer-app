package de.bikemaintainer.app.notifications

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import de.bikemaintainer.app.data.storage.SettingsStore
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.ui.FakeMaintenanceRepository
import de.bikemaintainer.app.ui.FakeVehicleRepository
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plain [Application]: the real app would start its own schedule sync in the background. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DueTasksWorkerTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val vehicles = FakeVehicleRepository()

    private class FakeScheduler : ReminderScheduler {
        var cancelCalls = 0
        override suspend fun sync() = Unit
        override fun cancel() {
            cancelCalls++
        }
    }

    private val scheduler = FakeScheduler()

    private suspend fun TestScope.worker(enabled: Boolean = true): DueTasksWorker {
        val settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { tmpFolder.newFile("t.preferences_pb") },
        )
        settings.savePortalLogin("https://bm.example/", "tok")
        settings.setNotificationsEnabled(enabled)
        val check = DueTaskCheck(vehicles, FakeMaintenanceRepository(), settings, FakeDueNotifications()) { true }
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                DueTasksWorker(appContext, workerParameters, check, scheduler)
        }
        return TestListenableWorkerBuilder<DueTasksWorker>(context).setWorkerFactory(factory).build()
    }

    @Test
    fun retryMapsToRetry() = runTest {
        vehicles.vehiclesResult = Outcome.Failure(AppError.Network)
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals(0, scheduler.cancelCalls)
    }

    @Test
    fun stopCancelsScheduleAndSucceeds() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker(enabled = false).doWork())
        assertEquals(1, scheduler.cancelCalls)
    }

    @Test
    fun doneSucceeds() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, scheduler.cancelCalls)
    }
}
