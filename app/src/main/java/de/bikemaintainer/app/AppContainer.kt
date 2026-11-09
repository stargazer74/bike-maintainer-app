package de.bikemaintainer.app

import android.content.Context
import android.util.Log
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.data.network.SessionState
import de.bikemaintainer.app.data.repository.ApiAuthRepository
import de.bikemaintainer.app.data.repository.AuthRepository
import de.bikemaintainer.app.data.repository.MaintenanceRepository
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.data.storage.DeviceTokenStore
import de.bikemaintainer.app.data.storage.KeystoreTokenCipher
import de.bikemaintainer.app.data.storage.SettingsStore
import de.bikemaintainer.app.data.storage.settingsDataStore
import java.io.File

/**
 * Manual dependency wiring of the data layer. Created exactly once in [BikeMaintainerApp.onCreate]: there must be
 * only one DataStore instance per file per process.
 */
class AppContainer(context: Context) {
    private val dataStore = settingsDataStore { File(context.filesDir, "datastore/settings.preferences_pb") }

    val settings = SettingsStore(dataStore)
    val sessionState = SessionState()

    private val tokens = DeviceTokenStore(settings, KeystoreTokenCipher())
    private val provider = ApiProvider(
        settings = settings,
        tokens = tokens,
        state = sessionState,
        debugLog = if (BuildConfig.DEBUG) { message -> Log.d("BikeMaintainerHttp", message) } else null,
    )

    val authRepository: AuthRepository = ApiAuthRepository(provider, settings, tokens, sessionState)
    val vehicleRepository = VehicleRepository(provider)
    val maintenanceRepository = MaintenanceRepository(provider)
}
