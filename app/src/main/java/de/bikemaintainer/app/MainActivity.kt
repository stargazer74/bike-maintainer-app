package de.bikemaintainer.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
                            container.sessionState.status,
                            container.reminderScheduler,
                            container.notifier,
                            { NotificationManagerCompat.from(applicationContext).areNotificationsEnabled() },
                            openVehicle.asStateFlow(),
                            { openVehicle.value = null },
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
        val id = intent?.getLongExtra(EXTRA_VEHICLE_ID, NO_VEHICLE) ?: NO_VEHICLE
        if (id != NO_VEHICLE) openVehicle.value = id
    }

    companion object {
        private const val NO_VEHICLE = -1L

        /** Id of the vehicle a notification wants to open. */
        const val EXTRA_VEHICLE_ID = "de.bikemaintainer.app.VEHICLE_ID"
    }
}
