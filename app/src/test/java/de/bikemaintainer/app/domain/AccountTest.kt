package de.bikemaintainer.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTest {
    @Test fun toStringDoesNotContainTheEmail() {
        val text = Account("max@example.org", null).toString()
        assertFalse(text, text.contains("max@example.org"))
        assertFalse(text, text.contains("example.org"))
    }

    @Test fun toStringDoesNotContainTheDisplayName() {
        val text = Account(null, "Chris").toString()
        assertFalse(text, text.contains("Chris"))
        assertTrue(text, text.startsWith("Account("))
    }
}
