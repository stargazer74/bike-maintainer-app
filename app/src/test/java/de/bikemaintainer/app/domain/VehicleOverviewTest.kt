package de.bikemaintainer.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleOverviewTest {
    private fun task(status: TaskStatus, active: Boolean = true, fraction: Double? = null, name: String = "T") =
        MaintenanceTask(1, 1, name, false, active, status, null, null, null, null, null, fraction)

    @Test fun overallStatusPrefersOverdue() {
        val tasks = listOf(task(TaskStatus.OK), task(TaskStatus.DUE_SOON), task(TaskStatus.OVERDUE))
        assertEquals(TaskStatus.OVERDUE, tasks.overallStatus())
        assertEquals(TaskStatus.DUE_SOON, tasks.filter { it.status != TaskStatus.OVERDUE }.overallStatus())
    }

    @Test fun overallStatusIgnoresInactiveTasks() {
        val tasks = listOf(task(TaskStatus.OVERDUE, active = false), task(TaskStatus.OK))
        assertEquals(TaskStatus.OK, tasks.overallStatus())
    }

    @Test fun onlyDoneTasksCountAsOk() {
        assertEquals(TaskStatus.OK, listOf(task(TaskStatus.DONE)).overallStatus())
    }

    @Test fun noActiveTasksHaveNoStatus() {
        assertNull(emptyList<MaintenanceTask>().overallStatus())
        assertNull(listOf(task(TaskStatus.OVERDUE, active = false)).overallStatus())
    }

    @Test fun dueCountCountsOverdueAndDueSoon() {
        val tasks = listOf(
            task(TaskStatus.OVERDUE), task(TaskStatus.DUE_SOON), task(TaskStatus.OK), task(TaskStatus.DONE),
            task(TaskStatus.OVERDUE, active = false),
        )
        assertEquals(2, tasks.dueCount())
    }

    @Test fun displayOrderIsStatusThenFractionThenName() {
        val okB = task(TaskStatus.OK, fraction = 0.5, name = "b")
        val done = task(TaskStatus.DONE, name = "a")
        val overdue = task(TaskStatus.OVERDUE, fraction = -0.2)
        val dueSoon = task(TaskStatus.DUE_SOON, fraction = 0.1)
        val okNull = task(TaskStatus.OK, name = "a")
        val okA = task(TaskStatus.OK, fraction = 0.5, name = "A")
        val inactive = task(TaskStatus.OVERDUE, active = false)
        val sorted = listOf(okB, done, overdue, dueSoon, okNull, okA, inactive).forDisplay()
        assertEquals(listOf(overdue, dueSoon, okA, okB, okNull, done), sorted)
    }

    @Test fun nullFractionsSortLastInBothComparisonDirections() {
        val a = task(TaskStatus.OK, name = "a")
        val b = task(TaskStatus.OK, fraction = 0.3, name = "b")
        assertEquals(listOf(b, a), listOf(a, b).forDisplay())
        assertEquals(listOf(b, a), listOf(b, a).forDisplay())
    }
}
