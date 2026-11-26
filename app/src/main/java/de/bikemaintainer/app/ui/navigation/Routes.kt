package de.bikemaintainer.app.ui.navigation

import kotlinx.serialization.Serializable

/** First start: online account or own server. */
@Serializable object SetupRoute

/** Address of an own server, typed in or scanned. */
@Serializable object OwnServerRoute

/** Camera that reads a server address from a QR code and hands it to [OwnServerRoute]. */
@Serializable object ScanServerRoute

/** SavedStateHandle key under which [ScanServerRoute] hands the scanned address to [OwnServerRoute]. */
const val SERVER_QR_KEY = "server_qr"

/** Portal: create an account on [serverUrl]. */
@Serializable data class RegisterRoute(val serverUrl: String)

@Serializable data class LoginRoute(val serverUrl: String, val relogin: Boolean = false)

@Serializable data class ProfilesRoute(val serverUrl: String)

@Serializable object VehiclesRoute

@Serializable object SettingsRoute

@Serializable object NotificationPermissionRoute

@Serializable data class VehicleRoute(val vehicleId: Long)

@Serializable data class LogMaintenanceRoute(val vehicleId: Long, val pendingId: String? = null)

/** Create a vehicle ([vehicleId] `null`) or edit one. */
@Serializable data class VehicleFormRoute(val vehicleId: Long? = null)

/** Create a maintenance task of [vehicleId] ([taskId] `null`) or edit one. */
@Serializable data class TaskFormRoute(val vehicleId: Long, val taskId: Long? = null)

/** SavedStateHandle key under which the log screen hands its [de.bikemaintainer.app.ui.logmaintenance.LogResult] name to the detail. */
const val LOG_RESULT_KEY = "log_result"

/** SavedStateHandle key under which the vehicle form asks the detail to delete the vehicle (confirmed there already). */
const val DELETE_VEHICLE_KEY = "delete_vehicle"

/** Photograph a filled-in worksheet for the log form of [vehicleId]; [templates] are the names that fit the vehicle. */
@Serializable data class ScanWorksheetRoute(val vehicleId: Long, val templates: List<String>)

/** Read the odometer of [vehicleId] from a photo; [lastMileage] is the vehicle's mileage for the plausibility check. */
@Serializable data class ScanOdometerRoute(val vehicleId: Long, val lastMileage: Int? = null)

/** SavedStateHandle key under which [ScanOdometerRoute] hands the taken-over mileage (Int) to the screen below. */
const val ODOMETER_RESULT_KEY = "odometer_result"

/** SavedStateHandle key under which the scan screen hands a [de.bikemaintainer.app.ui.logmaintenance.ScanTransfer] (JSON) to the log form. */
const val SCAN_RESULT_KEY = "scan_result"

/** Apply a maintenance plan template to [vehicleId] (Epic backend#150); online only. */
@Serializable data class TemplateRoute(val vehicleId: Long)

/** SavedStateHandle key under which the template screen hands the result (`created/skipped`) to the vehicle detail. */
const val TEMPLATE_RESULT_KEY = "template_result"
