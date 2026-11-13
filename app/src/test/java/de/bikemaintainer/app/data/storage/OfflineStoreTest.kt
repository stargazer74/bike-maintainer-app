package de.bikemaintainer.app.data.storage

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = Instant.parse("2026-11-13T20:40:00Z")
    private val strings = ListSerializer(String.serializer())

    private fun store(dir: File = File(tmp.root, "offline")) =
        FileOfflineStore(dir, Json, Clock.fixed(now, ZoneOffset.UTC))

    @Test fun storedAnswerComesBackWithItsFetchTime() = runBlocking {
        val s = store()
        s.save("vehicles", strings, listOf("a", "b"))
        assertEquals(Stored(listOf("a", "b"), now), s.load("vehicles", strings))
    }

    @Test fun survivesANewInstance() = runBlocking {
        store().save("tasks-1", strings, listOf("x"))
        assertEquals(listOf("x"), store().load("tasks-1", strings)?.value)
    }

    @Test fun nothingStoredIsNull() = runBlocking {
        assertNull(store().load("vehicles", strings))
    }

    @Test fun unreadableFileIsDropped() = runBlocking {
        val dir = File(tmp.root, "offline").apply { mkdirs() }
        val file = File(dir, "vehicles.json").apply { writeText("not json") }
        assertNull(store(dir).load("vehicles", strings))
        assertFalse(file.exists())
    }

    @Test fun answerOfAnotherShapeIsDropped() = runBlocking {
        val s = store()
        s.save("vehicles", String.serializer(), "one")
        assertNull(s.load("vehicles", strings))
        assertNull(s.load("vehicles", String.serializer()))
    }

    @Test fun retainKeepsOnlyWhatIsWanted() = runBlocking {
        val s = store()
        listOf("vehicles", "vehicle-1", "vehicle-2").forEach { s.save(it, strings, listOf(it)) }
        s.retain { it != "vehicle-2" }
        assertEquals(listOf("vehicle-1"), s.load("vehicle-1", strings)?.value)
        assertNull(s.load("vehicle-2", strings))
        assertEquals(listOf("vehicles"), s.load("vehicles", strings)?.value)
    }

    @Test fun clearRemovesEverything() = runBlocking {
        val dir = File(tmp.root, "offline")
        val s = store(dir)
        s.save("vehicles", strings, listOf("a"))
        s.save("tasks-1", strings, listOf("b"))
        s.clear()
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun keysMustBeSafeFileNames() {
        runBlocking { store().save("../escape", strings, emptyList()) }
    }
}
