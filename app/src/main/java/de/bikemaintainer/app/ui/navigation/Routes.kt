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
