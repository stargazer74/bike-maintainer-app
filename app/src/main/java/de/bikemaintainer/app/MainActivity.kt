package de.bikemaintainer.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import de.bikemaintainer.app.notifications.notificationsAllowed
import de.bikemaintainer.app.ui.navigation.AppDependencies
import de.bikemaintainer.app.ui.navigation.AppNavHost
import de.bikemaintainer.app.ui.navigation.readStartDestination
import de.bikemaintainer.app.ui.theme.AppSurface
import de.bikemaintainer.app.ui.theme.BikeMaintainerTheme

// AppCompatActivity: AppCompatDelegate.setApplicationLocales needs it below Android 13.
class MainActivity : AppCompatActivity() {
    /** Vehicle of the notification that started or resumed the app, until the navigation has handled it. */
    private val openVehicle = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Not after a recreation: the extra of the original intent was handled already.
        if (savedInstanceState == null) readVehicle(intent)
        enableEdgeToEdge()
        val container = (application as BikeMaintainerApp).container
        setContent {
            BikeMaintainerTheme {
                AppSurface {
                    // Read once; until then only the background. Unreadable settings start at the setup.
                    val start by produceState<Any?>(initialValue = null) {
                        value = readStartDestination { container.settings.current() }
                    }
                    val deps = remember(container) {
                        AppDependencies(
                            container.authRepository,
                            container.vehicleRepository,
                            container.maintenanceRepository,
                            container.measurementRepository,
                            container.sessionState.status,
                            container.reminderScheduler,
                            container.notifier,
                            { notificationsAllowed(applicationContext) },
                            openVehicle.asStateFlow(),
                            { openVehicle.value = null },
                            container.worksheetScanner,
                            container.pendingRepository,
                        )
                    }
                    start?.let { AppNavHost(deps = deps, start = it) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readVehicle(intent)
    }

    private fun readVehicle(intent: Intent?) {
        readVehicleExtra(intent)?.let { openVehicle.value = it }
    }

    companion object {
        private const val NO_VEHICLE = -1L

        /**
         * The vehicle of a notification tap, or null. The extra is removed once read, and ignored when the app was
         * relaunched from the Recents list (API 29/30 re-deliver the original intent then).
         */
        internal fun readVehicleExtra(intent: Intent?): Long? {
            if (intent == null) return null
            val id = intent.getLongExtra(EXTRA_VEHICLE_ID, NO_VEHICLE)
            intent.removeExtra(EXTRA_VEHICLE_ID)
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            return id.takeIf { it != NO_VEHICLE }
        }

        /** Id of the vehicle a notification wants to open. */
        const val EXTRA_VEHICLE_ID = "de.bikemaintainer.app.VEHICLE_ID"
    }
}
