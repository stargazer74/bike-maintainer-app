package de.bikemaintainer.app

import android.content.Context
import android.util.Log
import de.bikemaintainer.app.api.infrastructure.Serializer
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.data.network.SessionState
import de.bikemaintainer.app.data.repository.ApiAuthRepository
import de.bikemaintainer.app.data.repository.ApiMaintenanceRepository
import de.bikemaintainer.app.data.repository.ApiMeasurementRepository
import de.bikemaintainer.app.data.repository.ApiVehicleRepository
import de.bikemaintainer.app.data.repository.AuthRepository
import de.bikemaintainer.app.data.repository.MaintenanceRepository
import de.bikemaintainer.app.data.repository.MeasurementRepository
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.data.storage.DeviceTokenStore
import de.bikemaintainer.app.data.storage.FileOfflineStore
import de.bikemaintainer.app.data.storage.KeystoreTokenCipher
import de.bikemaintainer.app.data.storage.SettingsStore
import de.bikemaintainer.app.data.storage.settingsDataStore
import de.bikemaintainer.app.notifications.DueNotifications
import de.bikemaintainer.app.notifications.DueTaskCheck
import de.bikemaintainer.app.notifications.DueTaskNotifier
import de.bikemaintainer.app.notifications.ReminderScheduler
import de.bikemaintainer.app.notifications.WorkManagerReminderScheduler
import de.bikemaintainer.app.notifications.notificationsAllowed
import de.bikemaintainer.app.scan.AndroidWorksheetScanner
import de.bikemaintainer.app.ui.scan.WorksheetScanner
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

    /** Last server answers for use without a connection (app#64); app-private, cleared with the session. */
    private val offline = FileOfflineStore(File(context.filesDir, "offline"), Serializer.kotlinxSerializationJson)

    val authRepository: AuthRepository = ApiAuthRepository(provider, settings, tokens, sessionState, offline)
    val vehicleRepository: VehicleRepository = ApiVehicleRepository(provider, offline)
    val maintenanceRepository: MaintenanceRepository = ApiMaintenanceRepository(provider, offline)
    val measurementRepository: MeasurementRepository = ApiMeasurementRepository(provider, offline)

    private val notificationsAllowed = { notificationsAllowed(context) }
    val notifier: DueNotifications = DueTaskNotifier(context)
    val reminderScheduler: ReminderScheduler = WorkManagerReminderScheduler(context, settings, notificationsAllowed)
    /** Reads photographed worksheets on the device (Epic #120). */
    val worksheetScanner: WorksheetScanner = AndroidWorksheetScanner(context.applicationContext)
    val dueTaskCheck = DueTaskCheck(vehicleRepository, maintenanceRepository, settings, notifier, notificationsAllowed)
}
