package de.bikemaintainer.app.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessTest {
    private val today = LocalDate.of(2026, 11, 12)

    @Test fun mapsEveryStatusOfTheServer() {
        assertEquals(AccessStatus.TRIAL, Access.of("TRIAL", today, false).status)
        assertEquals(AccessStatus.ACTIVE, Access.of("ACTIVE", today, true).status)
        assertEquals(AccessStatus.EXPIRED, Access.of("EXPIRED", today, true).status)
        assertEquals(AccessStatus.FULL, Access.of("FULL", null, false).status)
    }

    @Test fun missingOrUnknownStatusMeansFullAccess() {
        assertEquals(Access.FULL, Access.of(null, null, null))
        assertEquals(AccessStatus.FULL, Access.of("SOMETHING_NEW", null, false).status)
    }

    @Test fun onlyExpiredIsReadOnly() {
        assertFalse(Access(AccessStatus.EXPIRED).canWrite)
        listOf(AccessStatus.TRIAL, AccessStatus.ACTIVE, AccessStatus.FULL).forEach { assertTrue(Access(it).canWrite) }
    }

    @Test fun scanningNeedsSubscriptionOrFullAccess() {
        assertTrue(Access(AccessStatus.ACTIVE).canScan)
        assertTrue(Access(AccessStatus.FULL).canScan)
        assertFalse(Access(AccessStatus.TRIAL).canScan)
        assertFalse(Access(AccessStatus.EXPIRED).canScan)
    }

    @Test fun trialDaysCountTheLastDayToo() {
        assertEquals(1, Access(AccessStatus.TRIAL, today).trialDaysLeft(today))
        assertEquals(8, Access(AccessStatus.TRIAL, today.plusDays(7)).trialDaysLeft(today))
        assertEquals(0, Access(AccessStatus.TRIAL, today.minusDays(1)).trialDaysLeft(today))
        assertNull(Access(AccessStatus.ACTIVE, today).trialDaysLeft(today))
        assertNull(Access(AccessStatus.TRIAL).trialDaysLeft(today))
    }

    @Test fun subscriptionRequiredIsOnlyThe403WithItsCode() {
        assertTrue(AppError.Http(403, "SUBSCRIPTION_REQUIRED").isSubscriptionRequired())
        assertFalse(AppError.Http(403, "WRONG_PASSWORD").isSubscriptionRequired())
        assertFalse(AppError.Http(400, "SUBSCRIPTION_REQUIRED").isSubscriptionRequired())
        assertFalse(AppError.Network.isSubscriptionRequired())
    }
}
