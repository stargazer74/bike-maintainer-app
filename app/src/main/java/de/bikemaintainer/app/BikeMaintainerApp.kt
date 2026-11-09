package de.bikemaintainer.app

import android.app.Application
import androidx.work.Configuration
import de.bikemaintainer.app.notifications.DueTasksWorkerFactory
import de.bikemaintainer.app.notifications.syncQuietly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** WorkManager is initialised on demand with this configuration (the default initializer is removed in the manifest). */
class BikeMaintainerApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(DueTasksWorkerFactory(container)).build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Brings the schedule in line with switch, permission and login, e.g. after the permission was revoked.
        appScope.launch { container.reminderScheduler.syncQuietly() }
    }
}
