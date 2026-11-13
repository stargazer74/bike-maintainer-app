package de.bikemaintainer.app.domain

import java.time.LocalDate

/** What was recorded without a connection and still has to reach the server (Epic backend#159, app#65). */
data class PendingItem(
    val id: String,
    val vehicleId: Long,
    val kind: Kind,
    /** The new mileage, or the mileage of the maintenance. */
    val mileage: Int,
    /** Day of the maintenance; null for a mileage. */
    val date: LocalDate? = null,
    val taskIds: Set<Long> = emptySet(),
    val state: PendingState = PendingState.Waiting,
) {
    enum class Kind { MILEAGE, MAINTENANCE }
}

sealed interface PendingState {
    /** Waits for a connection; sent automatically. */
    data object Waiting : PendingState

    /** The server already has a maintenance on [date] with the same tasks; sent only after the user confirms. */
    data class PossibleDuplicate(val date: LocalDate) : PendingState

    /** Cannot be sent as it is; the user discards it (nothing is lost silently). */
    data class Failed(val problem: PendingProblem) : PendingState
}

enum class PendingProblem {
    /** The vehicle was deleted (e.g. in the web app). */
    VEHICLE_GONE,

    /** The server rejected the entry, e.g. a task of it was deleted meanwhile. */
    REJECTED,

    /** The server already has a higher mileage. */
    MILEAGE_BELOW_CURRENT,

    /** The account is read-only. */
    SUBSCRIPTION_REQUIRED,
}

/** How a recording ended: on the server, or kept on the device until there is a connection. */
enum class Recorded { SAVED, QUEUED }
