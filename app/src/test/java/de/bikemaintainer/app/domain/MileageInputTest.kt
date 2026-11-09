package de.bikemaintainer.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MileageInputTest {
    @Test fun parsesDigits() {
        assertEquals(24600, parseMileage("24600"))
        assertEquals(0, parseMileage(" 0 "))
        assertEquals(9_999_999, parseMileage("9999999"))
    }

    @Test fun groupedNumberIsRejected() {
        assertNull(parseMileage("24.600"))
        assertNull(parseMileage("24,600"))
        assertNull(parseMileage("24 600"))
    }

    @Test fun invalidInputIsRejected() {
        assertNull(parseMileage(""))
        assertNull(parseMileage("-5"))
        assertNull(parseMileage("1e3"))
        assertNull(parseMileage("10000000"))
    }
}
