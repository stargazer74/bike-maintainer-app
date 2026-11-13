package de.bikemaintainer.app.data.storage

import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** A stored server answer and when it was fetched. */
data class Stored<T>(val value: T, val fetchedAt: Instant)

/**
 * The last server answers per key, so the app can show them without a connection (Epic backend#159, app#64).
 * Holds only what the offline view needs and belongs to one person on one server: it is cleared on every login,
 * profile choice, logout, server switch and account deletion. Implemented by [FileOfflineStore]; [None] keeps nothing.
 */
interface OfflineStore {
    suspend fun <T> save(key: String, serializer: KSerializer<T>, value: T)

    /** The stored answer, or null if there is none or it cannot be read (then it is dropped). */
    suspend fun <T> load(key: String, serializer: KSerializer<T>): Stored<T>?

    suspend fun remove(key: String)

    /** Drops every key for which [keep] is false. */
    suspend fun retain(keep: (String) -> Boolean)

    suspend fun clear()

    /** Stores nothing: the behaviour before offline support, used where offline data must not be kept (tests). */
    object None : OfflineStore {
        override suspend fun <T> save(key: String, serializer: KSerializer<T>, value: T) = Unit

        override suspend fun <T> load(key: String, serializer: KSerializer<T>): Stored<T>? = null

        override suspend fun remove(key: String) = Unit

        override suspend fun retain(keep: (String) -> Boolean) = Unit

        override suspend fun clear() = Unit
    }
}

/**
 * One JSON file per key in [dir] (app-private storage, excluded from backups by `allowBackup=false`). Keys are
 * restricted to `[a-z0-9-]` so they are safe file names. Write errors are swallowed: offline data is a convenience,
 * a failure to store it must never fail the online call it came from.
 */
class FileOfflineStore(
    private val dir: File,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) : OfflineStore {
    @Serializable
    private class Entry(val fetchedAt: Long, val value: JsonElement)

    private val mutex = Mutex()

    override suspend fun <T> save(key: String, serializer: KSerializer<T>, value: T) = io {
        try {
            val entry = Entry(clock.millis(), json.encodeToJsonElement(serializer, value))
            dir.mkdirs()
            val tmp = File(dir, "${checked(key)}.tmp")
            tmp.writeText(json.encodeToString(Entry.serializer(), entry))
            if (!tmp.renameTo(file(key))) tmp.delete()
        } catch (_: IOException) {
            // intentionally ignored: see class doc
        } catch (_: SerializationException) {
            // intentionally ignored: see class doc
        }
    }

    override suspend fun <T> load(key: String, serializer: KSerializer<T>): Stored<T>? = io {
        val f = file(key)
        if (!f.exists()) return@io null
        try {
            val entry = json.decodeFromString(Entry.serializer(), f.readText())
            Stored(json.decodeFromJsonElement(serializer, entry.value), Instant.ofEpochMilli(entry.fetchedAt))
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            f.delete() // e.g. written by an older app version with another model
            null
        } catch (_: IllegalArgumentException) {
            f.delete()
            null
        }
    }

    override suspend fun remove(key: String) = io { file(key).delete(); Unit }

    override suspend fun retain(keep: (String) -> Boolean) = io {
        dir.listFiles()?.forEach { f -> if (!keep(f.name.substringBeforeLast('.'))) f.delete() }
        Unit
    }

    override suspend fun clear() = io {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    private fun file(key: String) = File(dir, "${checked(key)}.json")

    private fun checked(key: String): String {
        require(KEY.matches(key)) { "invalid offline key: $key" }
        return key
    }

    private suspend fun <R> io(block: () -> R): R = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private companion object {
        val KEY = Regex("[a-z0-9-]+")
    }
}
