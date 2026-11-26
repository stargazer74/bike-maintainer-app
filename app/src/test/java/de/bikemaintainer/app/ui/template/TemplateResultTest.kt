package de.bikemaintainer.app.ui.template

import de.bikemaintainer.app.domain.TemplateApplyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TemplateResultTest {
    @Test fun roundTrips() {
        assertEquals(TemplateApplyResult(3, 1), parseTemplateResult(TemplateApplyResult(3, 1).toNavValue()))
    }

    @Test fun unreadableValuesAreIgnored() {
        listOf(null, "", "3", "a/b", "1/2/3", "1/").forEach { assertNull(it, parseTemplateResult(it)) }
    }
}
