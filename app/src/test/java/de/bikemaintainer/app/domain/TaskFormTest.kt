package de.bikemaintainer.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskFormTest {
    @Test fun recurringTaskNeedsAnInterval() {
        val draft = TaskDraft(name = "Ölwechsel")
        assertEquals(setOf(TaskField.NO_INTERVAL), draft.invalidFields())
        assertTrue(draft.copy(intervalKm = "6000").invalidFields().isEmpty())
        assertTrue(draft.copy(intervalMonths = "12").invalidFields().isEmpty())
    }

    @Test fun validDraftBecomesTheInput() {
        val draft = TaskDraft(name = " Ölwechsel ", description = "  ", intervalKm = "6000", intervalMonths = "12", firstDueKm = "1000")
        assertEquals(TaskInput("Ölwechsel", null, 6000, 12, 1000, null, oneTime = false, active = true), draft.toInput())
    }

    @Test fun intervalsAreAtLeastOneFirstDueAtLeastZero() {
        val draft = TaskDraft(name = "Kette", intervalKm = "0", intervalMonths = "x", firstDueKm = "0", firstDueMonths = "-1")
        assertEquals(setOf(TaskField.INTERVAL_KM, TaskField.INTERVAL_MONTHS, TaskField.FIRST_DUE_MONTHS), draft.invalidFields())
        assertNull(draft.toInput())
    }

    @Test fun oneTimeTaskIgnoresHiddenIntervals() {
        val draft = TaskDraft(name = "Reparatur", intervalKm = "0", intervalMonths = "12", oneTime = true)
        assertTrue(draft.invalidFields().isEmpty())
        assertEquals(TaskInput("Reparatur", null, null, null, null, null, oneTime = true, active = true), draft.toInput())
    }

    @Test fun nameAndDescriptionAreChecked() {
        val draft = TaskDraft(name = "", description = "d".repeat(1001), intervalKm = "1")
        assertEquals(setOf(TaskField.NAME, TaskField.DESCRIPTION), draft.invalidFields())
    }

    @Test fun switchingATaskKeepsEverythingElse() {
        val task = MaintenanceTask(
            5, 7, "Ventile", false, true, TaskStatus.OK, null, null, null, null, null, null,
            description = "alle 4", intervalKm = 24000, intervalMonths = null, firstDueKm = 12000, firstDueMonths = null,
        )
        assertEquals(TaskInput("Ventile", "alle 4", 24000, null, 12000, null, oneTime = false, active = false), task.withActive(false))
        assertEquals(TaskDraft("Ventile", "alle 4", "24000", "", "12000", "", oneTime = false, active = true), TaskDraft.of(task))
    }
}
