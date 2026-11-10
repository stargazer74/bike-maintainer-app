package de.bikemaintainer.app.domain

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecimalInputTest {
    private val max = BigDecimal("9.999")

    private fun valid(text: String) = (parseDecimal(text, max) as DecimalInput.Valid).value

    @Test fun acceptsCommaAndPoint() {
        assertEquals(BigDecimal("0.15"), valid("0,15"))
        assertEquals(BigDecimal("0.15"), valid("0.15"))
        assertEquals(BigDecimal("0.5"), valid(",5"))
        assertEquals(BigDecimal("2."), valid("2,"))
        assertEquals(BigDecimal("1"), valid(" 1 "))
    }

    @Test fun emptyIsNotAnError() {
        assertEquals(DecimalInput.Empty, parseDecimal("  ", max))
        assertNull(parseDecimal("", max).valueOrNull())
    }

    @Test fun rejectsFormatRangeAndDecimals() {
        listOf("abc", "1,2,3", "-1", "1.000,5", "1e3", ".", "1 2").forEach {
            assertEquals(it, DecimalInput.Invalid(DecimalError.FORMAT), parseDecimal(it, max))
        }
        assertEquals(DecimalInput.Invalid(DecimalError.RANGE), parseDecimal("10", max))
        assertEquals(DecimalInput.Invalid(DecimalError.DECIMALS), parseDecimal("0,1234", max))
        assertEquals("trailing zeros do not count", BigDecimal("0.1500"), valid("0,1500"))
        assertNull(parseDecimal("abc", max).valueOrNull())
    }
}
