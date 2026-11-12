package de.bikemaintainer.app.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Access of the account (Epic backend#136); household mode and admins always have [FULL]. */
enum class AccessStatus { TRIAL, ACTIVE, EXPIRED, FULL }

/**
 * What the account may do. [until] is the last day with full access for [AccessStatus.TRIAL] (inclusive, as the
 * server sends it) and the day access ended for [AccessStatus.EXPIRED]; [hadSubscription] tells "trial over" from
 * "access over".
 */
data class Access(
    val status: AccessStatus = AccessStatus.FULL,
    val until: LocalDate? = null,
    val hadSubscription: Boolean = false,
) {
    /** Read-only once trial or subscription ended: nothing can be recorded or changed any more. */
    val canWrite: Boolean get() = status != AccessStatus.EXPIRED

    /** Worksheets printed during the trial carry a demo code, so scanning needs full access. */
    val canScan: Boolean get() = status == AccessStatus.ACTIVE || status == AccessStatus.FULL

    /** Days of the trial left including today, `null` outside the trial. */
    fun trialDaysLeft(today: LocalDate): Int? {
        if (status != AccessStatus.TRIAL || until == null) return null
        return (ChronoUnit.DAYS.between(today, until) + 1).coerceAtLeast(0).toInt()
    }

    companion object {
        val FULL = Access()

        /** Error code of the server for a write without trial or subscription (HTTP 403). */
        const val SUBSCRIPTION_REQUIRED = "SUBSCRIPTION_REQUIRED"

        /** From the server's fields; a missing status (older server) means full access, never a lock. */
        fun of(status: String?, until: LocalDate?, hasSubscription: Boolean?): Access = Access(
            status = AccessStatus.entries.firstOrNull { it.name == status } ?: AccessStatus.FULL,
            until = until,
            hadSubscription = hasSubscription == true,
        )
    }
}

/** The access of the signed-in account, shared by the screens; filled by `GET auth/me`. */
interface AccessSource {
    val access: StateFlow<Access>

    /** Asks the server again; a failure keeps the last known access. */
    suspend fun refreshAccess()

    /** A write was refused with [Access.SUBSCRIPTION_REQUIRED]: the account is read-only from now on. */
    fun subscriptionRequired()

    companion object {
        /** Full access that never changes, e.g. for tests that are not about it. */
        val Unrestricted: AccessSource = object : AccessSource {
            override val access: StateFlow<Access> = MutableStateFlow(Access.FULL).asStateFlow()

            override suspend fun refreshAccess() = Unit

            override fun subscriptionRequired() = Unit
        }
    }
}

/** Whether a failed write means the account is read-only. */
fun AppError.isSubscriptionRequired(): Boolean =
    this is AppError.Http && status == 403 && code == Access.SUBSCRIPTION_REQUIRED
