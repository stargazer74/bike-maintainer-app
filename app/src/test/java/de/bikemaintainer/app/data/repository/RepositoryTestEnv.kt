package de.bikemaintainer.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import de.bikemaintainer.app.data.storage.TokenCipher
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.data.network.SessionState
import de.bikemaintainer.app.data.storage.DeviceTokenStore
import de.bikemaintainer.app.data.storage.FakeTokenCipher
import de.bikemaintainer.app.data.storage.SettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/** Real stores on a temporary DataStore plus a MockWebServer; shared by the repository tests. */
class RepositoryTestEnv(
    file: File,
    cipher: TokenCipher = FakeTokenCipher(),
    dataStoreWrap: (DataStore<Preferences>) -> DataStore<Preferences> = { it },
    handler: (RecordedRequest) -> MockResponse,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val server = MockWebServer()
    val requests = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())
    val settings = SettingsStore(dataStoreWrap(PreferenceDataStoreFactory.create(scope = scope) { file }))
    val tokens = DeviceTokenStore(settings, cipher)
    val state = SessionState()
    val provider = ApiProvider(settings, tokens, state, null)

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handler(request)
            }
        }
        server.start()
    }

    val url: String get() = server.url("/").toString()

    fun close() {
        server.close()
        scope.cancel()
    }

    companion object {
        fun json(body: String, code: Int = 200): MockResponse =
            MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
    }
}
