package de.bikemaintainer.app.notifications

import de.bikemaintainer.app.data.repository.MaintenanceRepository
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.data.storage.SettingsStore
import de.bikemaintainer.app.data.storage.StoredSettings
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.dueUpdates
import kotlinx.coroutines.flow.first

/** What the background job does next: [DONE] until the next period, [RETRY] with backoff, [STOP] the schedule. */
enum class CheckResult { DONE, RETRY, STOP }

/** A login is stored: a device token (portal) or a profile (household). */
internal fun StoredSettings.hasLogin() = encryptedDeviceToken != null || profileId != null

/** Whether reminders should run at all: switch on, notifications allowed and a login stored. */
internal suspend fun remindersWanted(settings: SettingsStore, notificationsAllowed: () -> Boolean): Boolean {
    val stored = settings.current()
    return stored.notificationsEnabled && notificationsAllowed() && stored.hasLogin()
}

/**
 * One run of the daily check: loads all vehicles and their tasks and notifies what became due since the last report.
 * Nothing is reported or stored unless everything loaded, so a failure midway never leads to partial notifications.
 */
class DueTaskCheck(
    private val vehicles: VehicleRepository,
    private val maintenance: MaintenanceRepository,
    private val settings: SettingsStore,
    private val notifications: DueNotifications,
    private val notificationsAllowed: () -> Boolean,
) {
    suspend fun run(): CheckResult {
        if (!remindersWanted(settings, notificationsAllowed)) return CheckResult.STOP
        val list = when (val result = vehicles.vehicles()) {
            is Outcome.Failure -> return onFailure(result.error)
            is Outcome.Success -> result.value
        }
        val current = mutableListOf<Pair<Vehicle, List<MaintenanceTask>>>()
        for (vehicle in list) {
            when (val result = maintenance.tasks(vehicle.id)) {
                // Deleted between the list and this call: nothing to remind about.
                is Outcome.Failure -> if (result.error != AppError.NotFound) return onFailure(result.error)
                is Outcome.Success -> current += vehicle to result.value
            }
        }
        // Logged out (or switched off) while loading: report nothing for a session that is gone.
        if (!remindersWanted(settings, notificationsAllowed)) return CheckResult.STOP
        val update = dueUpdates(settings.reportedDue.first(), current)
        update.toNotify.forEach(notifications::showDue)
        // Completed, deleted or back to OK: the old notification would only mislead.
        update.noneDue.forEach(notifications::cancelVehicle)
        // Deleted in the web app, on another device or in this app while the check ran: its notification would open
        // a vehicle that is gone.
        notifications.cancelVehiclesExcept(list.map { it.id }.toSet())
        settings.setReportedDue(update.newState)
        // Everything loaded, so the session works: a pending "please sign in again" is obsolete.
        notifications.cancelRelogin()
        return CheckResult.DONE
    }

    private suspend fun onFailure(error: AppError): CheckResult = when (error) {
        AppError.Network -> CheckResult.RETRY
        is AppError.Http -> if (error.status >= 500 || error.status == -1) CheckResult.RETRY else CheckResult.DONE
        AppError.Unauthorized -> {
            if (!settings.current().reloginNotified) {
                notifications.showRelogin()
                settings.setReloginNotified(true)
            }
            CheckResult.STOP
        }
        AppError.NotConfigured, AppError.Storage -> CheckResult.STOP
        // Not found, cleartext outside the home network, …: nothing to do today, the next run tries again.
        AppError.NotFound, AppError.CleartextNotAllowed, AppError.InvalidAddress -> CheckResult.DONE
    }
}
