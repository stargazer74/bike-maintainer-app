package de.bikemaintainer.app.features.support

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import de.bikemaintainer.app.api.infrastructure.Serializer
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.data.network.ErrorIdState
import de.bikemaintainer.app.data.network.SessionState
import de.bikemaintainer.app.data.repository.ApiAuthRepository
import de.bikemaintainer.app.data.repository.ApiMaintenanceRepository
import de.bikemaintainer.app.data.repository.ApiMeasurementRepository
import de.bikemaintainer.app.data.repository.ApiVehicleRepository

import de.bikemaintainer.app.data.repository.OutboxSync
import de.bikemaintainer.app.data.repository.PendingRepository
import de.bikemaintainer.app.data.storage.DeviceTokenStore
import de.bikemaintainer.app.data.storage.FakeTokenCipher
import de.bikemaintainer.app.data.storage.FileOfflineStore
import de.bikemaintainer.app.data.storage.FileOutbox
import de.bikemaintainer.app.data.storage.SettingsStore
import de.bikemaintainer.app.data.storage.settingsDataStore
import de.bikemaintainer.app.notifications.FakeDueNotifications
import de.bikemaintainer.app.ui.FakeReminderScheduler
import de.bikemaintainer.app.ui.common.ErrorIdNotice
import de.bikemaintainer.app.ui.navigation.AppDependencies
import de.bikemaintainer.app.ui.navigation.AppNavHost
import de.bikemaintainer.app.ui.navigation.readStartDestination
import de.bikemaintainer.app.ui.theme.AppSurface
import de.bikemaintainer.app.ui.theme.BikeMaintainerTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * The app as `AppContainer` + `MainActivity` wire it, with app-private storage in [dir]. Only what needs real hardware
 * is replaced: the Android Keystore (fake cipher), WorkManager (sending is triggered by a step via [outboxSync]),
 * notifications and the camera (none in scenarios).
 */
class TestApp(dir: File) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val settings = SettingsStore(settingsDataStore(scope) { File(dir, "datastore/settings.preferences_pb") })
    val session = SessionState()
    val errorIds = ErrorIdState()
    val tokens = DeviceTokenStore(settings, FakeTokenCipher())
    private val provider = ApiProvider(settings, tokens, session, null, errorIds)
    private val offline = FileOfflineStore(File(dir, "offline"), Serializer.kotlinxSerializationJson)
    private val outbox = FileOutbox(File(dir, "outbox/entries.json"), Serializer.kotlinxSerializationJson)
    private val owner = settings.settings.map { it.owner }
    val pending = PendingRepository(outbox, owner)
    val outboxSync = OutboxSync(provider, outbox, owner)
    val auth = ApiAuthRepository(provider, settings, tokens, session, offline, pending)
    private val vehicles = ApiVehicleRepository(provider, offline, pending)
    private val maintenance = ApiMaintenanceRepository(provider, offline, pending)
    private val measurements = ApiMeasurementRepository(provider, offline)
    private val openVehicle = MutableStateFlow<Long?>(null)

    private val deps = AppDependencies(
        auth,
        vehicles,
        maintenance,
        measurements,
        session.status,
        FakeReminderScheduler(),
        FakeDueNotifications(),
        { true },
        openVehicle.asStateFlow(),
        { openVehicle.value = null },
        pending = pending,
    )

    /** The app's navigation, so steps can wait until a page accepts taps (RESUMED). */
    var navController: NavHostController? = null
        private set

    /** Ends the DataStore's scope, so scenarios do not pile up running stores. */
    fun close() = scope.cancel()

    /** The content of `MainActivity`. */
    fun content(): @androidx.compose.runtime.Composable () -> Unit = {
        BikeMaintainerTheme(darkTheme = false) {
            AppSurface {
                val start by produceState<Any?>(initialValue = null) {
                    value = readStartDestination { settings.current() }
                }
                val errorId by errorIds.errorId.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    val nav = rememberNavController().also { navController = it }
                    start?.let { AppNavHost(deps = deps, start = it, navController = nav) }
                    ErrorIdNotice(errorId = errorId, onDismiss = errorIds::dismiss, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}
