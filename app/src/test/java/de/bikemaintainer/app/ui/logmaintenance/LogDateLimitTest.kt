package de.bikemaintainer.app.ui.logmaintenance

import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogDateLimitTest {
    private val today = LocalDate.of(2026, 11, 9)

    private fun utcMillis(date: LocalDate) = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun check() {
        assertTrue(isSelectableLogDate(utcMillis(today), today))
        assertTrue(isSelectableLogDate(utcMillis(today.minusDays(1)), today))
        assertFalse(isSelectableLogDate(utcMillis(today.plusDays(1)), today))
    }

    private fun inZone(id: String) {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            check()
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test fun utcDefault() = inZone("UTC")

    @Test fun behindUtc() = inZone("America/Los_Angeles")

    @Test fun aheadOfUtc() = inZone("Pacific/Auckland")
}
