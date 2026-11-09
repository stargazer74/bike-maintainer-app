package de.bikemaintainer.app.ui.vehicle

import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.TaskStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskStatusTextTest {
    private fun task(
        status: TaskStatus,
        km: Int? = null,
        days: Int? = null,
        lastKm: Int? = null,
        lastDate: LocalDate? = null,
    ) = MaintenanceTask(1, 7, "Oil", false, true, status, null, km, days, lastKm, lastDate, null)

    @Test fun overdueKmShowsPositiveAmount() {
        assertEquals(
            TaskLabel.Remaining(TaskLabel.Kind.OVERDUE, Axis.KM, 350),
            task(TaskStatus.OVERDUE, km = -350).label(),
        )
    }

    @Test fun dueSoonDays() {
        assertEquals(
            TaskLabel.Remaining(TaskLabel.Kind.DUE_SOON, Axis.DAYS, 12),
            task(TaskStatus.DUE_SOON, days = 12).label(),
        )
    }

    @Test fun okNeverPerformedIsFirstDue() {
        assertEquals(
            TaskLabel.Remaining(TaskLabel.Kind.FIRST_DUE, Axis.KM, 800),
            task(TaskStatus.OK, km = 800).label(),
        )
    }

    @Test fun okWithHistoryIsOk() {
        assertEquals(
            TaskLabel.Remaining(TaskLabel.Kind.OK, Axis.DAYS, 40),
            task(TaskStatus.OK, days = 40, lastDate = LocalDate.of(2026, 1, 1)).label(),
        )
    }

    @Test fun kmWinsOverDays() {
        val label = task(TaskStatus.OK, km = 100, days = 5, lastKm = 1).label() as TaskLabel.Remaining
        assertEquals(Axis.KM, label.axis)
    }

    @Test fun withoutRemainingValues() {
        assertEquals(TaskLabel.NeverPerformed, task(TaskStatus.OK).label())
        assertEquals(TaskLabel.Ok, task(TaskStatus.OK, lastKm = 5).label())
        assertEquals(TaskLabel.Ok, task(TaskStatus.OK, lastDate = LocalDate.of(2026, 1, 1)).label())
    }

    @Test fun doneUsesLastServiceDate() {
        assertEquals(
            TaskLabel.DoneOn(LocalDate.of(2026, 10, 1)),
            task(TaskStatus.DONE, lastDate = LocalDate.of(2026, 10, 1)).label(),
        )
        assertEquals(TaskLabel.Done, task(TaskStatus.DONE).label())
    }
}
