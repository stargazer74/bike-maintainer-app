package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.data.storage.InMemoryOutbox
import de.bikemaintainer.app.data.storage.Outbox
import de.bikemaintainer.app.data.storage.OutboxEntry
import de.bikemaintainer.app.domain.PendingItem
import de.bikemaintainer.app.domain.PendingLog
import de.bikemaintainer.app.domain.PendingProblem
import de.bikemaintainer.app.domain.PendingState
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
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
        // Not stored: the question stays and can be answered again.
        if (!tryStore { outbox.update(id) { it.copy(confirmed = true) } }) return
        scheduler.schedule()
    }

    /** False if it could not be removed (storage error): it stays in the list. */
    suspend fun discard(id: String): Boolean = tryStore { outbox.update(id) { null } }

    private inline fun tryStore(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (_: IOException) {
        false
    }

    /**
     * The maintenance [id] to edit it in the form (app#70); null if it is gone (sent or discarded meanwhile).
     * @throws IOException if the stored entries cannot be read right now.
     */
    suspend fun log(id: String): PendingLog? {
        val entry = outbox.load().firstOrNull { it.id == id } ?: return null
        val log = entry.log ?: return null
        return PendingLog(
            date = log.performedAt,
            mileage = log.mileageAtPerformed,
            taskIds = log.performedTaskIds.orEmpty(),
            taskNames = entry.taskNames,
            notes = log.notes,
            measurements = log.measurements?.toDomain(),
            raiseMileageTo = entry.raiseMileageTo,
        )
    }

    /**
     * Replaces the maintenance [id] with the edited [log] and sends it again (app#70). It keeps its id, which is its
     * `clientEntryId`: if an earlier attempt reached the server, that log is updated with the edit instead of a second
     * one being created (app#96). Saving an edited possible duplicate confirms it. False if the entry is gone or could
     * not be stored.
     */
    suspend fun replaceLog(id: String, log: PendingLog): Boolean {
        var found = false
        try {
            outbox.update(id) { entry ->
                if (entry.log == null) return@update entry
                found = true
                entry.copy(
                    log = logRequest(UUID.fromString(id), log.date, log.mileage, log.taskIds, log.notes, log.measurements),
                    raiseMileageTo = log.raiseMileageTo,
                    taskNames = log.taskNames.filterKeys { it in log.taskIds },
                    confirmed = entry.confirmed || entry.duplicateOn != null,
                    problem = null,
                    edited = true,
                )
            }
        } catch (_: IOException) {
            return false
        }
        if (found) scheduler.schedule()
        return found
    }

    /** After a new login: sends what was waiting for it. */
    suspend fun scheduleIfPending() {
        // Unreadable right now: the run tries again and retries until it can read them.
        val pending = try {
            outbox.load().isNotEmpty()
        } catch (_: IOException) {
            true
        }
        if (pending) scheduler.schedule()
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
