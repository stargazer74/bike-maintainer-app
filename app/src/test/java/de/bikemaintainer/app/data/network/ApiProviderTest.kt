package de.bikemaintainer.app.data.network

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import de.bikemaintainer.app.data.storage.DeviceTokenStore
import de.bikemaintainer.app.data.storage.FakeTokenCipher
import de.bikemaintainer.app.data.storage.SettingsStore
import de.bikemaintainer.app.domain.ServerMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApiProviderTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun provider(): Pair<ApiProvider, SettingsStore> {
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s.preferences_pb") })
        return ApiProvider(settings, DeviceTokenStore(settings, FakeTokenCipher()), SessionState(), null) to settings
    }

    @Test
    fun baseUrlIsNormalized() {
        assertEquals("https://host:8443/", ApiProvider.normalize("https://host:8443").toString())
        assertEquals("https://host:8443/", ApiProvider.normalize(" https://host:8443// ").toString())
        assertEquals("http://192.168.178.20:8080/", ApiProvider.normalize("http://192.168.178.20:8080/").toString())
    }

    @Test
    fun cachesPerNormalizedUrlAndResets() {
        val (provider, _) = provider()
        val first = provider.apisFor("https://host:8443")
        assertSame(first, provider.apisFor("https://host:8443//"))
        assertNotSame(first, provider.apisFor("https://other:8443"))
        provider.reset()
        assertNotSame(first, provider.apisFor("https://host:8443"))
    }

    @Test
    fun apisUsesStoredServerOrIsNull() {
        val (provider, settings) = provider()
        assertNull(runBlocking { provider.apis() })
        runBlocking { settings.setServer("https://host:8443/", ServerMode.PORTAL) }
        assertSame(provider.apisFor("https://host:8443"), runBlocking { provider.apis() })
    }
}
