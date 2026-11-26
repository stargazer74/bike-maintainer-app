package de.bikemaintainer.app.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleTemplateTest {
    private val today = LocalDate.of(2026, 11, 26)

    private fun task(
        key: String,
        oneTime: Boolean = false,
        firstDueKm: Int? = null,
        firstDueMonths: Int? = null,
    ) = TemplateTask(key, key, null, oneTime, 10000, 12, firstDueKm, firstDueMonths, emptyList(), emptyList())

    private fun template(vararg tasks: TemplateTask) = VehicleTemplate(
        "t1", VehicleType.MOTORCYCLE, "Honda", "CB 650 R", 2019, null, tasks.size, TemplateSourceQuality.MANUFACTURER,
        null, emptyList(), tasks.toList(),
    )

    private val vehicle = Vehicle(
        1, "Bike", VehicleType.MOTORCYCLE, "Honda", "CB", 2020, LocalDate.of(2020, 1, 31), 5000,
    )

    @Test
    fun recurringTasksAreSelectedEvenIfFirstDuePassed() {
        val rows = initialRows(template(task("oil", firstDueKm = 1000)), emptySet(), vehicle, today)
        assertTrue(rows.single().selected)
        assertFalse(rows.single().existing)
    }

    @Test
    fun oneTimeTaskWithPassedFirstDueKmIsNotSelected() {
        val rows = initialRows(template(task("run-in", true, firstDueKm = 5000)), emptySet(), vehicle, today)
        assertFalse(rows.single().selected)
    }

    @Test
    fun oneTimeTaskWithKmNotYetReachedIsSelected() {
        val rows = initialRows(template(task("run-in", true, firstDueKm = 5001)), emptySet(), vehicle, today)
        assertTrue(rows.single().selected)
    }

    @Test
    fun oneTimeTaskWithPassedFirstDueMonthsIsNotSelected() {
        val rows = initialRows(template(task("valves", true, firstDueMonths = 60)), emptySet(), vehicle, today)
        assertFalse(rows.single().selected)
    }

    @Test
    fun firstDueMonthsOnTheDayItselfCountsAsPassed() {
        // 2020-11-26 + 72 months = 2026-11-26 = today (passed); +73 months = 2026-12-26
        val on = vehicle.copy(firstRegistrationDate = LocalDate.of(2020, 11, 26))
        assertFalse(initialRows(template(task("a", true, firstDueMonths = 72)), emptySet(), on, today).single().selected)
        assertTrue(initialRows(template(task("a", true, firstDueMonths = 73)), emptySet(), on, today).single().selected)
    }

    @Test
    fun monthEndIsClamped() {
        // 2020-01-31 + 82 months = 2026-11-30 (the 31st clamped to the end of the month)
        val v = vehicle.copy(firstRegistrationDate = LocalDate.of(2020, 1, 31))
        val rows = initialRows(template(task("a", true, firstDueMonths = 82)), emptySet(), v, LocalDate.of(2026, 11, 30))
        assertFalse(rows.single().selected)
        val notYet = initialRows(template(task("a", true, firstDueMonths = 82)), emptySet(), v, LocalDate.of(2026, 11, 29))
        assertTrue(notYet.single().selected)
    }

    @Test
    fun oneTimeTaskWithoutFirstDueIsSelected() {
        assertTrue(initialRows(template(task("a", true)), emptySet(), vehicle, today).single().selected)
    }

    @Test
    fun existingKeysAreMarkedAndNotSelected() {
        val rows = initialRows(template(task("oil"), task("chain")), setOf("oil"), vehicle, today)
        assertTrue(rows[0].existing)
        assertFalse(rows[0].selected)
        assertFalse(rows[1].existing)
        assertTrue(rows[1].selected)
    }

    @Test
    fun fillEmptyFillsOnlyNullFieldsOfNewRows() {
        val rows = listOf(
            TemplateTaskRow(task("a"), true, false, 100, null),
            TemplateTaskRow(task("b"), true, false, null, LocalDate.of(2025, 1, 1)),
            TemplateTaskRow(task("c"), false, true),
        )
        val filled = fillEmpty(rows, 5000, today)
        assertEquals(100, filled[0].lastDoneKm)
        assertEquals(today, filled[0].lastDoneDate)
        assertEquals(5000, filled[1].lastDoneKm)
        assertEquals(LocalDate.of(2025, 1, 1), filled[1].lastDoneDate)
        assertNull(filled[2].lastDoneKm)
        assertNull(filled[2].lastDoneDate)
        assertNotSame(rows[2], filled[2])
        assertNull(rows[0].lastDoneDate)
    }

    @Test
    fun fillEmptyKeepsAnExplicitZero() {
        val filled = fillEmpty(listOf(TemplateTaskRow(task("a"), true, false, 0, null)), 5000, today)
        assertEquals(0, filled.single().lastDoneKm)
        assertEquals(today, filled.single().lastDoneDate)
    }

    @Test
    fun toApplyRequestKeepsAZero() {
        val request = toApplyRequest("t1", listOf(TemplateTaskRow(task("a"), true, false, 0, null)))
        assertEquals(listOf(AppliedTask("a", 0, null)), request.tasks)
    }

    @Test
    fun toApplyRequestTakesSelectedNewRowsAndPassesEmptyFieldsAsNull() {
        val rows = listOf(
            TemplateTaskRow(task("a"), true, false, 100, LocalDate.of(2025, 1, 1)),
            TemplateTaskRow(task("b"), true, false),
            TemplateTaskRow(task("c"), false, false, 1, null),
            TemplateTaskRow(task("d"), true, true, 1, null),
        )
        assertEquals(
            TemplateApplication("t1", listOf(AppliedTask("a", 100, LocalDate.of(2025, 1, 1)), AppliedTask("b", null, null))),
            toApplyRequest("t1", rows),
        )
    }

    @Test
    fun reportUrlEncodesAndOmitsEmptyTask() {
        val base = "https://github.com/stargazer74/bike-maintainer-templates/issues/new?template=fehler-in-vorlage.yml"
        assertEquals("$base&vorlage=honda%2Fcb", reportUrl("honda/cb"))
        assertEquals("$base&vorlage=honda%2Fcb", reportUrl("honda/cb", null))
        assertEquals("$base&vorlage=t1&aufgabe=oil%20change%26x", reportUrl("t1", "oil change&x"))
    }

    @Test
    fun onlyWebAddressesOfASourceAreOpenable() {
        fun source(url: String?) = TemplateSource("s", TemplateSourceKind.FORUM, "t", today, url, null)
        assertEquals("https://x.test/a", source("https://x.test/a").openableUrl())
        assertEquals("http://x.test", source("http://x.test").openableUrl())
        assertNull(source("javascript:alert(1)").openableUrl())
        assertNull(source("intent://x#Intent;end").openableUrl())
        assertNull(source(null).openableUrl())
    }

    @Test
    fun searchParamsNeedMakeOfTwoCharsAndModel() {
        assertNull(templateSearchParams("K", "CB", VehicleType.CAR, null))
        assertNull(templateSearchParams(" K ", "CB", VehicleType.CAR, null))
        assertNull(templateSearchParams("Honda", "  ", VehicleType.CAR, null))
        assertNull(templateSearchParams("Honda", null, VehicleType.CAR, null))
        assertNull(templateSearchParams(null, "CB", VehicleType.CAR, null))
        assertEquals(
            TemplateSearchParams("BMW", VehicleType.MOTORCYCLE, "R 1250", 2021),
            templateSearchParams(" BMW ", " R 1250 ", VehicleType.MOTORCYCLE, 2021),
        )
    }
}
