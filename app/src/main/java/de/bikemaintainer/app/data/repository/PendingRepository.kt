package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.data.storage.InMemoryOutbox
import de.bikemaintainer.app.data.storage.Outbox
import de.bikemaintainer.app.data.storage.OutboxEntry
import de.bikemaintainer.app.domain.PendingItem
import de.bikemaintainer.app.domain.PendingProblem
import de.bikemaintainer.app.domain.PendingState
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Starts sending the outbox once there is a connection; implemented with WorkManager. */
fun interface OutboxScheduler {
    fun schedule()
}

/**
 * The recordings waiting to be sent (app#65), as the screens see them, and what the user can do with them. The data
 * layer adds entries itself when a save fails for lack of a connection ([enqueue]).
 */
class PendingRepository(
    private val outbox: Outbox = InMemoryOutbox(),
    private val scheduler: OutboxScheduler = OutboxScheduler {},
) {
    val items: Flow<List<PendingItem>> = outbox.entries.map { list -> list.map { it.toItem() } }

    /** Keeps [entry] for later and starts sending once there is a connection; false if it could not be stored. */
    suspend fun enqueue(entry: OutboxEntry): Boolean = try {
        outbox.add(entry)
        scheduler.schedule()
        true
    } catch (_: IOException) {
        false
    }

    /** Sends a possible duplicate anyway. */
    suspend fun confirm(id: String) {
        outbox.update(id) { it.copy(confirmed = true) }
        scheduler.schedule()
    }

    suspend fun discard(id: String) = outbox.update(id) { null }

    /** After a new login: sends what was waiting for it. */
    suspend fun scheduleIfPending() {
        if (outbox.load().isNotEmpty()) scheduler.schedule()
    }

    suspend fun count(): Int = outbox.load().size

    suspend fun clear() = outbox.clear()
}

internal fun OutboxEntry.toItem(): PendingItem {
    val state = when {
        problem != null -> PendingState.Failed(
            PendingProblem.entries.firstOrNull { it.name == problem } ?: PendingProblem.REJECTED,
        )
        duplicateOn != null && !confirmed -> PendingState.PossibleDuplicate(LocalDate.parse(duplicateOn))
        else -> PendingState.Waiting
    }
    val log = log
    return if (log != null) {
        PendingItem(
            id = id,
            vehicleId = vehicleId,
            kind = PendingItem.Kind.MAINTENANCE,
            mileage = log.mileageAtPerformed,
            date = log.performedAt,
            taskIds = log.performedTaskIds.orEmpty(),
            state = state,
        )
    } else {
        PendingItem(id = id, vehicleId = vehicleId, kind = PendingItem.Kind.MILEAGE, mileage = mileage ?: 0, state = state)
    }
}
