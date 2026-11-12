package de.bikemaintainer.app.notifications

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import de.bikemaintainer.app.data.repository.MaintenanceRepository
import de.bikemaintainer.app.data.storage.SettingsStore
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.DueTask
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleDue
import de.bikemaintainer.app.domain.VehicleType
import de.bikemaintainer.app.ui.FakeMaintenanceRepository
import de.bikemaintainer.app.ui.FakeVehicleRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeDueNotifications : DueNotifications {
    val shown = mutableListOf<VehicleDue>()
    var reloginShown = 0
    var cancelAllCalls = 0
    var cancelReloginCalls = 0
    val cancelledVehicles = mutableListOf<Long>()

    override fun showDue(due: VehicleDue) {
        shown += due
    }

    override fun showRelogin() {
        reloginShown++
    }

    override fun cancelRelogin() {
        cancelReloginCalls++
    }

    override fun cancelVehicle(vehicleId: Long) {
        cancelledVehicles += vehicleId
    }

    /** Vehicle ids passed to [cancelVehiclesExcept], one set per call. */
    val keptVehicles = mutableListOf<Set<Long>>()

    override fun cancelVehiclesExcept(vehicleIds: Set<Long>) {
        keptVehicles += vehicleIds
    }

    override fun cancelAll() {
        cancelAllCalls++
    }
}

/** Per-vehicle task results on top of [FakeMaintenanceRepository]. */
private class PerVehicleMaintenance(val results: Map<Long, Outcome<List<MaintenanceTask>>>) {
    val repo = object : MaintenanceRepository by FakeMaintenanceRepository() {
        override suspend fun tasks(vehicleId: Long) = results.getValue(vehicleId)
    }
}

class DueTaskCheckTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val vehicles = FakeVehicleRepository()
    private var maintenance = PerVehicleMaintenance(emptyMap())
    private val notifications = FakeDueNotifications()
    private var allowed = true

    private fun vehicle(id: Long, name: String) =
        Vehicle(id, name, VehicleType.MOTORCYCLE, null, null, 2020, LocalDate.of(2020, 1, 1), 1000)

    private fun task(id: Long, vehicleId: Long, status: TaskStatus) =
        MaintenanceTask(id, vehicleId, "Task $id", false, true, status, null, null, null, null, null, 0.5)

    private suspend fun TestScope.store(loggedIn: Boolean = true): SettingsStore {
        val store = SettingsStore(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { tmpFolder.newFile("t.preferences_pb") },
        )
        if (loggedIn) store.savePortalLogin("https://bm.example/", "tok")
        return store
    }

    private fun check(settings: SettingsStore) =
        DueTaskCheck(vehicles, maintenance.repo, settings, notifications) { allowed }

    private fun twoVehicles(tasksOf2: Outcome<List<MaintenanceTask>>) {
        vehicles.vehiclesResult = Outcome.Success(listOf(vehicle(1, "A"), vehicle(2, "B")))
        maintenance = PerVehicleMaintenance(
            mapOf(
                1L to Outcome.Success(listOf(task(10, 1, TaskStatus.OVERDUE), task(11, 1, TaskStatus.OK))),
                2L to tasksOf2,
            ),
        )
    }

    @Test
    fun newDueTasksAreNotifiedAndStored() = runTest {
        val settings = store()
        twoVehicles(Outcome.Success(listOf(task(20, 2, TaskStatus.DUE_SOON))))
        assertEquals(CheckResult.DONE, check(settings).run())
        assertEquals(
            listOf(
                VehicleDue(1, "A", listOf(DueTask(10, "Task 10", TaskStatus.OVERDUE))),
                VehicleDue(2, "B", listOf(DueTask(20, "Task 20", TaskStatus.DUE_SOON))),
            ),
            notifications.shown,
        )
        assertEquals(mapOf(10L to TaskStatus.OVERDUE, 20L to TaskStatus.DUE_SOON), settings.reportedDue.first())
    }

    @Test
    fun secondRunDoesNotNotifyAgain() = runTest {
        val settings = store()
        twoVehicles(Outcome.Success(listOf(task(20, 2, TaskStatus.DUE_SOON))))
        check(settings).run()
        notifications.shown.clear()
        assertEquals(CheckResult.DONE, check(settings).run())
        assertTrue(notifications.shown.isEmpty())
    }

    @Test
    fun secondRunWithOneNewTaskListsTheOlderDueTaskToo() = runTest {
        val settings = store()
        vehicles.vehiclesResult = Outcome.Success(listOf(vehicle(1, "A")))
        maintenance = PerVehicleMaintenance(mapOf(1L to Outcome.Success(listOf(task(10, 1, TaskStatus.OVERDUE)))))
        check(settings).run()
        notifications.shown.clear()
        maintenance = PerVehicleMaintenance(
            mapOf(1L to Outcome.Success(listOf(task(10, 1, TaskStatus.OVERDUE), task(11, 1, TaskStatus.DUE_SOON)))),
        )
        check(settings).run()
        val shown = notifications.shown.single()
        assertEquals(listOf(DueTask(11, "Task 11", TaskStatus.DUE_SOON)), shown.tasks)
        assertEquals(listOf(11L, 10L), shown.all.map { it.id })
    }

    @Test
    fun vehicleWithoutDueTasksLeftIsCancelled() = runTest {
        val settings = store()
        twoVehicles(Outcome.Success(listOf(task(20, 2, TaskStatus.OK))))
        assertEquals(CheckResult.DONE, check(settings).run())
        assertEquals(listOf(2L), notifications.cancelledVehicles)
    }

    /** Only the vehicles that still exist keep a notification; one deleted on the web loses it. */
    @Test
    fun notificationsOfDeletedVehiclesAreRemoved() = runTest {
        val settings = store()
        twoVehicles(Outcome.Success(emptyList()))
        check(settings).run()
        assertEquals(listOf(setOf(1L, 2L)), notifications.keptVehicles)
    }

    @Test
    fun successfulRunCancelsReloginNotification() = runTest {
        val settings = store()
        twoVehicles(Outcome.Success(emptyList()))
        check(settings).run()
        assertEquals(1, notifications.cancelReloginCalls)
    }

    @Test
    fun failedRunCancelsNothing() = runTest {
        val settings = store()
        twoVehicles(Outcome.Failure(AppError.Network))
        check(settings).run()
        assertTrue(notifications.cancelledVehicles.isEmpty())
        assertTrue(notifications.keptVehicles.isEmpty())
        assertEquals(0, notifications.cancelReloginCalls)
    }

    @Test
    fun failureMidwayKeepsStateAndRetries() = runTest {
        val settings = store()
        settings.setReportedDue(mapOf(99L to TaskStatus.DUE_SOON))
        twoVehicles(Outcome.Failure(AppError.Network))
        assertEquals(CheckResult.RETRY, check(settings).run())
        assertTrue(notifications.shown.isEmpty())
        assertEquals(mapOf(99L to TaskStatus.DUE_SOON), settings.reportedDue.first())
    }

    @Test
    fun serverErrorRetries() = runTest {
        val settings = store()
        vehicles.vehiclesResult = Outcome.Failure(AppError.Http(503, null))
        assertEquals(CheckResult.RETRY, check(settings).run())
    }

    @Test
    fun unreadableResponseRetries() = runTest {
        val settings = store()
        vehicles.vehiclesResult = Outcome.Failure(AppError.Http(-1, null))
        assertEquals(CheckResult.RETRY, check(settings).run())
    }

    @Test
    fun unauthorizedShowsReloginAndStops() = runTest {
        val settings = store()
        vehicles.vehiclesResult = Outcome.Failure(AppError.Unauthorized)
        assertEquals(CheckResult.STOP, check(settings).run())
        assertEquals(1, notifications.reloginShown)
        assertTrue(settings.current().reloginNotified)
    }

    @Test
    fun reloginNotifiedOnlyOnce() = runTest {
        val settings = store()
        twoVehicles(Outcome.Failure(AppError.Unauthorized))
        assertEquals(CheckResult.STOP, check(settings).run())
        assertEquals(CheckResult.STOP, check(settings).run())
        assertEquals(1, notifications.reloginShown)
        assertTrue(notifications.shown.isEmpty())
    }

    @Test
    fun notConfiguredStops() = runTest {
        val settings = store()
        vehicles.vehiclesResult = Outcome.Failure(AppError.NotConfigured)
        assertEquals(CheckResult.STOP, check(settings).run())
        assertEquals(0, notifications.reloginShown)
    }

    @Test
    fun storageErrorStops() = runTest {
        val settings = store()
        vehicles.vehiclesResult = Outcome.Failure(AppError.Storage)
        assertEquals(CheckResult.STOP, check(settings).run())
    }

    @Test
    fun switchedOffDoesNotTouchNetwork() = runTest {
        val settings = store()
        settings.setNotificationsEnabled(false)
        assertEquals(CheckResult.STOP, check(settings).run())
        assertEquals(0, vehicles.vehiclesCalls)
    }

    @Test
    fun withoutPermissionDoesNotTouchNetwork() = runTest {
        val settings = store()
        allowed = false
        assertEquals(CheckResult.STOP, check(settings).run())
        assertEquals(0, vehicles.vehiclesCalls)
    }

    @Test
    fun signedOutDoesNotTouchNetwork() = runTest {
        val settings = store(loggedIn = false)
        assertEquals(CheckResult.STOP, check(settings).run())
        assertEquals(0, vehicles.vehiclesCalls)
    }

    @Test
    fun householdProfileCountsAsLogin() = runTest {
        val settings = store(loggedIn = false)
        settings.saveHouseholdProfile("http://192.168.0.2:8080/", 7)
        assertEquals(CheckResult.DONE, check(settings).run())
        assertEquals(1, vehicles.vehiclesCalls)
    }

    @Test
    fun clientErrorEndsQuietly() = runTest {
        val settings = store()
        twoVehicles(Outcome.Failure(AppError.Http(400, null)))
        assertEquals(CheckResult.DONE, check(settings).run())
        assertTrue(notifications.shown.isEmpty())
        assertEquals(0, notifications.reloginShown)
        assertEquals(emptyMap<Long, TaskStatus>(), settings.reportedDue.first())
    }

    @Test
    fun vehicleDeletedMeanwhileIsSkipped() = runTest {
        val settings = store()
        twoVehicles(Outcome.Success(listOf(task(20, 2, TaskStatus.DUE_SOON))))
        maintenance = PerVehicleMaintenance(
            mapOf(1L to Outcome.Failure(AppError.NotFound), 2L to Outcome.Success(listOf(task(20, 2, TaskStatus.DUE_SOON)))),
        )
        assertEquals(CheckResult.DONE, check(settings).run())
        assertEquals(listOf(VehicleDue(2, "B", listOf(DueTask(20, "Task 20", TaskStatus.DUE_SOON)))), notifications.shown)
        assertEquals(mapOf(20L to TaskStatus.DUE_SOON), settings.reportedDue.first())
    }

    @Test
    fun loggedOutWhileLoadingNotifiesAndStoresNothing() = runTest {
        val settings = store()
        vehicles.vehiclesResult = Outcome.Success(listOf(vehicle(1, "A")))
        val repo = object : MaintenanceRepository by FakeMaintenanceRepository() {
            override suspend fun tasks(vehicleId: Long): Outcome<List<MaintenanceTask>> {
                settings.clearSession()
                return Outcome.Success(listOf(task(10, 1, TaskStatus.OVERDUE)))
            }
        }
        val result = DueTaskCheck(vehicles, repo, settings, notifications) { allowed }.run()
        assertEquals(CheckResult.STOP, result)
        assertTrue(notifications.shown.isEmpty())
        assertEquals(emptyMap<Long, TaskStatus>(), settings.reportedDue.first())
    }
}
