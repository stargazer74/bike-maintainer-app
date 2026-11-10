package de.bikemaintainer.app.ui.navigation

import kotlinx.serialization.Serializable

@Serializable object SetupRoute

@Serializable data class LoginRoute(val serverUrl: String, val relogin: Boolean = false)

@Serializable data class ProfilesRoute(val serverUrl: String)

@Serializable object VehiclesRoute

@Serializable object SettingsRoute

@Serializable object NotificationPermissionRoute

@Serializable data class VehicleRoute(val vehicleId: Long)

@Serializable data class LogMaintenanceRoute(val vehicleId: Long)

/** SavedStateHandle key under which the log screen hands its [de.bikemaintainer.app.ui.logmaintenance.LogResult] name to the detail. */
const val LOG_RESULT_KEY = "log_result"

/** Photograph a filled-in worksheet for the log form of [vehicleId]; [templates] are the names that fit the vehicle. */
@Serializable data class ScanWorksheetRoute(val vehicleId: Long, val templates: List<String>)

/** SavedStateHandle key under which the scan screen hands a [de.bikemaintainer.app.ui.logmaintenance.ScanTransfer] (JSON) to the log form. */
const val SCAN_RESULT_KEY = "scan_result"
