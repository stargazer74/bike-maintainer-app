package de.bikemaintainer.app.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DueNotificationsTest {
    private fun vehicle(id: Long, name: String) =
        Vehicle(id, name, VehicleType.MOTORCYCLE, null, null, 2020, LocalDate.of(2020, 1, 1), 1000)

    private fun task(id: Long, vehicleId: Long, status: TaskStatus, active: Boolean = true, fraction: Double? = 0.5) =
        MaintenanceTask(id, vehicleId, "Task $id", false, active, status, null, null, null, null, null, fraction)

    private val a = vehicle(1, "A")
    private val b = vehicle(2, "B")

    private fun current(vararg tasksA: MaintenanceTask, tasksB: List<MaintenanceTask> = emptyList()) =
        listOf(a to tasksA.toList(), b to tasksB)

    private fun due(id: Long, status: TaskStatus) = DueTask(id, "Task $id", status)

    @Test
    fun newlyDueIsReported() {
        val update = dueUpdates(
            emptyMap(),
            current(
                task(1, 1, TaskStatus.OVERDUE),
                task(2, 1, TaskStatus.DUE_SOON),
                task(3, 1, TaskStatus.OK),
            ),
        )
        assertEquals(
            listOf(VehicleDue(1, "A", listOf(due(1, TaskStatus.OVERDUE), due(2, TaskStatus.DUE_SOON)))),
            update.toNotify,
        )
        assertEquals(mapOf(1L to TaskStatus.OVERDUE, 2L to TaskStatus.DUE_SOON), update.newState)
    }

    @Test
    fun dueSoonToOverdueIsReported() {
        val update = dueUpdates(mapOf(2L to TaskStatus.DUE_SOON), current(task(2, 1, TaskStatus.OVERDUE)))
        assertEquals(listOf(VehicleDue(1, "A", listOf(due(2, TaskStatus.OVERDUE)))), update.toNotify)
    }

    @Test
    fun unchangedStateNotifiesNothing() {
        val previous = mapOf(1L to TaskStatus.OVERDUE, 2L to TaskStatus.DUE_SOON)
        val update = dueUpdates(previous, current(task(1, 1, TaskStatus.OVERDUE), task(2, 1, TaskStatus.DUE_SOON)))
        assertTrue(update.toNotify.isEmpty())
        assertEquals(previous, update.newState)
    }

    @Test
    fun overdueToDueSoonIsNotReportedButStored() {
        val update = dueUpdates(mapOf(1L to TaskStatus.OVERDUE), current(task(1, 1, TaskStatus.DUE_SOON)))
        assertTrue(update.toNotify.isEmpty())
        assertEquals(mapOf(1L to TaskStatus.DUE_SOON), update.newState)
    }

    @Test
    fun doneTaskIsReportedAgainLater() {
        val run1 = dueUpdates(emptyMap(), current(task(1, 1, TaskStatus.OVERDUE)))
        assertEquals(1, run1.toNotify.size)
        val run2 = dueUpdates(run1.newState, current(task(1, 1, TaskStatus.DONE)))
        assertTrue(run2.toNotify.isEmpty())
        assertEquals(emptyMap<Long, TaskStatus>(), run2.newState)
        val run3 = dueUpdates(run2.newState, current(task(1, 1, TaskStatus.DUE_SOON)))
        assertEquals(listOf(VehicleDue(1, "A", listOf(due(1, TaskStatus.DUE_SOON)))), run3.toNotify)
    }

    @Test
    fun inactiveTasksAreIgnored() {
        val update = dueUpdates(emptyMap(), current(task(4, 1, TaskStatus.OVERDUE, active = false)))
        assertTrue(update.toNotify.isEmpty())
        assertEquals(emptyMap<Long, TaskStatus>(), update.newState)
    }

    @Test
    fun overdueComesBeforeDueSoonThenDisplayOrder() {
        val update = dueUpdates(
            emptyMap(),
            current(
                task(1, 1, TaskStatus.DUE_SOON, fraction = 0.1),
                task(2, 1, TaskStatus.OVERDUE, fraction = 0.3),
                task(3, 1, TaskStatus.OVERDUE, fraction = 0.2),
            ),
        )
        assertEquals(listOf(3L, 2L, 1L), update.toNotify.single().tasks.map { it.id })
    }

    @Test
    fun oneNotificationPerVehicle() {
        val update = dueUpdates(
            emptyMap(),
            current(task(1, 1, TaskStatus.OVERDUE), tasksB = listOf(task(5, 2, TaskStatus.DUE_SOON))),
        )
        assertEquals(listOf(1L, 2L), update.toNotify.map { it.vehicleId })
        assertEquals("B", update.toNotify[1].vehicleName)
    }
}
