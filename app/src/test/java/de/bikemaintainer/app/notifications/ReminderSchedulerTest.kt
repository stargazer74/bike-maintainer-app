package de.bikemaintainer.app.notifications

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import de.bikemaintainer.app.data.storage.SettingsStore
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plain [Application]: the real app would start its own schedule sync in the background. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReminderSchedulerTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val berlin = ZoneId.of("Europe/Berlin")

    @Before
    fun initWorkManager() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun closeWorkManager() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, berlin)

    @Test
    fun delayUntilNextMorning() {
        assertEquals(Duration.ofMinutes(30), delayUntilNextMorning(at(2026, 11, 9, 6, 30)))
        assertEquals(Duration.ofHours(24), delayUntilNextMorning(at(2026, 11, 9, 7)))
        assertEquals(Duration.ofHours(9), delayUntilNextMorning(at(2026, 11, 9, 22)))
        // Clocks go forward in the night to 28 March 2027: one hour less.
        assertEquals(Duration.ofHours(8), delayUntilNextMorning(at(2027, 3, 27, 22)))
    }

    private suspend fun TestScope.store(loggedIn: Boolean = true): SettingsStore {
        val store = SettingsStore(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { tmpFolder.newFile("t.preferences_pb") },
        )
        if (loggedIn) store.saveHouseholdProfile("http://192.168.0.2:8080/", 7)
        return store
    }

    private fun work() = WorkManager.getInstance(context).getWorkInfosForUniqueWork("due_tasks").get()

    private fun isScheduled() = work().any { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun syncEnqueuesWhenAllowed() = runTest {
        WorkManagerReminderScheduler(context, store()) { true }.sync()
        val info = work().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals(Duration.ofHours(24).toMillis(), info.periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun syncKeepsExistingSchedule() = runTest {
        val scheduler = WorkManagerReminderScheduler(context, store()) { true }
        scheduler.sync()
        val first = work().single().id
        scheduler.sync()
        assertEquals(first, work().single().id)
    }

    @Test
    fun syncCancelsWhenSwitchedOff() = runTest {
        val settings = store()
        val scheduler = WorkManagerReminderScheduler(context, settings) { true }
        scheduler.sync()
        settings.setNotificationsEnabled(false)
        scheduler.sync()
        assertFalse(isScheduled())
    }

    @Test
    fun syncCancelsWithoutPermission() = runTest {
        val settings = store()
        var allowed = true
        val scheduler = WorkManagerReminderScheduler(context, settings) { allowed }
        scheduler.sync()
        allowed = false
        scheduler.sync()
        assertFalse(isScheduled())
    }

    @Test
    fun syncCancelsWhenSignedOut() = runTest {
        val settings = store()
        val scheduler = WorkManagerReminderScheduler(context, settings) { true }
        scheduler.sync()
        settings.clearSession()
        scheduler.sync()
        assertFalse(isScheduled())
    }
}
