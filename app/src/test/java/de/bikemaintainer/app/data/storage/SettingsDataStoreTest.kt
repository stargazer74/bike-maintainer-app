package de.bikemaintainer.app.data.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsDataStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun corruptFileIsReplacedByEmptySettings() {
        val file = tmp.newFile("settings.preferences_pb")
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x55, 0x66)) // not a preferences proto
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = SettingsStore(settingsDataStore(scope) { file })
            assertEquals(StoredSettings(null, null, null, null), runBlocking { store.current() })
        } finally {
            scope.cancel()
        }
    }
}
