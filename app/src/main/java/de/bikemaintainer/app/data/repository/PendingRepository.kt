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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart

/** Starts sending the outbox once there is a connection; implemented with WorkManager. */
fun interface OutboxScheduler {
    fun schedule()

    /** Stops a run that is waiting or under way (logout, another person signs in). */
    fun cancel() {}
}

/** How long recordings of a person who is not signed in are kept for them (app#97). */
internal val FOREIGN_ENTRIES_KEPT_MS = TimeUnit.DAYS.toMillis(30)

/** Entries from before app#97 have no owner and belong to whoever is signed in. */
internal fun OutboxEntry.belongsTo(owner: String?) = this.owner == null || this.owner == owner

/**
 * Marks entries of other people as hidden from [now] on (and unmarks the own ones: their person is back), and drops
 * those hidden for longer than [FOREIGN_ENTRIES_KEPT_MS] – data minimisation (app#97).
 */
internal suspend fun Outbox.tidyUp(owner: String?, now: Long) = updateAll { entry ->
    val hiddenSince = entry.hiddenSince
    when {
        entry.belongsTo(owner) -> if (hiddenSince == null) entry else entry.copy(hiddenSince = null)
        hiddenSince == null -> entry.copy(hiddenSince = now)
        now - hiddenSince > FOREIGN_ENTRIES_KEPT_MS -> null
        else -> entry
    }
}

/**
 * The recordings waiting to be sent (app#65), as the screens see them, and what the user can do with them. The data
 * layer adds entries itself when a save fails for lack of a connection ([enqueue]).
 *
 * Every entry belongs to the person who recorded it ([owner] at that time, app#97). Only that person sees it, and only
 * under their session is it sent. If someone else signs in (a session ran out, nobody logged out), the entries wait
 * hidden for their person to come back, for at most [FOREIGN_ENTRIES_KEPT_MS].
 */
class PendingRepository(
    private val outbox: Outbox = InMemoryOutbox(),
    /** [de.bikemaintainer.app.data.storage.StoredSettings.owner]: who is signed in. */
    private val owner: Flow<String?> = flowOf(null),
    private val now: () -> Long = System::currentTimeMillis,
    private val scheduler: OutboxScheduler = OutboxScheduler {},
) {
    /** Opening a list of them also drops what was kept too long for someone else. */
    val items: Flow<List<PendingItem>> = combine(outbox.entries, owner) { list, owner ->
        list.filter { it.belongsTo(owner) }.map { it.toItem() }
    }.onStart { tryStore { outbox.tidyUp(owner.first(), now()) } }

    /** Keeps [entry] for later and starts sending once there is a connection; false if it could not be stored. */
    suspend fun enqueue(entry: OutboxEntry): Boolean = try {
        outbox.add(entry.copy(owner = owner.first()))
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

    /**
     * After a new login or profile choice: a run started for the previous person stops, entries of other people are
     * hidden (and dropped after a while), and what is waiting for the person now signed in is sent.
     */
    suspend fun sessionStarted() {
        scheduler.cancel()
        scheduleIfPending()
    }

    /** At app start and after a new login: sends what is waiting for the person signed in, tidies up the rest. */
    suspend fun scheduleIfPending() {
        val owner = owner.first()
        // Unreadable right now: the run tries again and retries until it can read them.
        val pending = try {
            outbox.tidyUp(owner, now())
            outbox.load().any { it.belongsTo(owner) }
        } catch (_: IOException) {
            true
        }
        if (pending) scheduler.schedule()
    }

    /** The entries of the person signed in, which a logout would drop. */
    suspend fun count(): Int {
        val owner = owner.first()
        return outbox.load().count { it.belongsTo(owner) }
    }

    /**
     * Logout or account deletion of [owner]: stops sending and drops their entries; those of other people stay. If
     * [owner] is unknown, everything is dropped. A storage error leaves the entries hidden until the next try.
     */
    suspend fun clearFor(owner: String?) {
        scheduler.cancel()
        tryStore { if (owner == null) outbox.clear() else outbox.updateAll { if (it.belongsTo(owner)) null else it } }
    }

    /** Server switch: drops everyone's entries. */
    suspend fun clearAll() {
        scheduler.cancel()
        tryStore { outbox.clear() }
    }
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
