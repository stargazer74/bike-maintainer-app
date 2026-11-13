package de.bikemaintainer.app.data.storage

import de.bikemaintainer.app.api.models.MaintenanceLogRequest
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One recording made without a connection (app#65): either a new [mileage] or a maintenance [log] (whose
 * `clientEntryId` is [id], so resending it never creates a duplicate). Kept in the order it was recorded.
 */
@Serializable
data class OutboxEntry(
    val id: String,
    val vehicleId: Long,
    val createdAt: Long,
    val mileage: Int? = null,
    val log: MaintenanceLogRequest? = null,
    /** After the log is saved, the vehicle's mileage is raised to this (the "raise mileage" choice of the form). */
    val raiseMileageTo: Int? = null,
    /** The user confirmed sending it although the server has a maintenance on the same day with the same tasks. */
    val confirmed: Boolean = false,
    /** ISO date of the possible duplicate on the server; set while waiting for the user's decision. */
    val duplicateOn: String? = null,
    /** [de.bikemaintainer.app.domain.PendingProblem] name: cannot be sent as it is, waits to be discarded. */
    val problem: String? = null,
) {
    /** Sent by the next sync: no open question, no problem. */
    val ready: Boolean get() = problem == null && (duplicateOn == null || confirmed)
}

/**
 * The entries waiting to be sent. Unlike [OfflineStore] this is user data that exists nowhere else yet: it survives a
 * new login (the same person signing in again after the session ran out) and is only cleared on logout, server switch
 * and account deletion, after a warning.
 */
interface Outbox {
    val entries: StateFlow<List<OutboxEntry>>

    /** Reads the stored entries if not done yet; every other call does this itself. */
    suspend fun load(): List<OutboxEntry>

    suspend fun add(entry: OutboxEntry)

    /** Replaces the entry [id] with [change] of it; `null` removes it. Unknown ids are ignored. */
    suspend fun update(id: String, change: (OutboxEntry) -> OutboxEntry?)

    suspend fun clear()
}

/** Keeps the entries in memory only: for tests and previews. */
open class InMemoryOutbox(initial: List<OutboxEntry> = emptyList()) : Outbox {
    private val _entries = MutableStateFlow(initial)
    override val entries: StateFlow<List<OutboxEntry>> = _entries.asStateFlow()
    private val mutex = Mutex()

    override suspend fun load(): List<OutboxEntry> = _entries.value

    override suspend fun add(entry: OutboxEntry) = mutex.withLock { _entries.value = _entries.value + entry }

    override suspend fun update(id: String, change: (OutboxEntry) -> OutboxEntry?) = mutex.withLock {
        _entries.value = _entries.value.mapNotNull { if (it.id == id) change(it) else it }
    }

    override suspend fun clear() = mutex.withLock { _entries.value = emptyList() }
}

/**
 * The entries as one JSON file in app-private storage (no backup). Written atomically (temporary file, rename), so a
 * crash while writing keeps the previous state. Unlike the offline cache a write error is reported: losing an entry
 * the user recorded must not go unnoticed.
 */
class FileOutbox(private val file: File, private val json: Json) : Outbox {
    private val _entries = MutableStateFlow<List<OutboxEntry>>(emptyList())
    override val entries: StateFlow<List<OutboxEntry>> = _entries.asStateFlow()
    private val mutex = Mutex()
    private var loaded = false
    private val serializer = ListSerializer(OutboxEntry.serializer())

    override suspend fun load(): List<OutboxEntry> = locked { _entries.value }

    /** @throws IOException if the entry cannot be stored. */
    override suspend fun add(entry: OutboxEntry) = locked { write(_entries.value + entry) }

    override suspend fun update(id: String, change: (OutboxEntry) -> OutboxEntry?) = locked {
        write(_entries.value.mapNotNull { if (it.id == id) change(it) else it })
    }

    override suspend fun clear() = locked {
        listOf("", ".tmp", ".unreadable").forEach { File(file.parentFile, file.name + it).delete() }
        _entries.value = emptyList()
    }

    private suspend fun <R> locked(block: () -> R): R = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                _entries.value = read()
                loaded = true
            }
            block()
        }
    }

    private fun read(): List<OutboxEntry> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (_: IOException) {
            emptyList()
        } catch (_: SerializationException) {
            // Unreadable (e.g. written by an incompatible version): keep it aside instead of overwriting it.
            file.renameTo(File(file.parentFile, file.name + ".unreadable"))
            emptyList()
        } catch (_: IllegalArgumentException) {
            file.renameTo(File(file.parentFile, file.name + ".unreadable"))
            emptyList()
        }
    }

    private fun write(list: List<OutboxEntry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, list))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("could not store the outbox")
        }
        _entries.value = list
    }
}
