package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.AppConfigResponse
import de.bikemaintainer.app.api.models.MaintenanceTaskResponse
import de.bikemaintainer.app.api.models.MaintenanceTaskStatus
import de.bikemaintainer.app.api.models.OperatingMode
import de.bikemaintainer.app.api.models.ProfileResponse
import de.bikemaintainer.app.api.models.VehicleResponse
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Profile
import de.bikemaintainer.app.domain.ServerConfig
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleType
import de.bikemaintainer.app.api.models.VehicleType as ApiVehicleType

internal fun ApiVehicleType.toDomain(): VehicleType = when (this) {
    ApiVehicleType.MOTORCYCLE -> VehicleType.MOTORCYCLE
    ApiVehicleType.CAR -> VehicleType.CAR
    ApiVehicleType.BICYCLE -> VehicleType.BICYCLE
    ApiVehicleType.E_BIKE -> VehicleType.E_BIKE
    ApiVehicleType.QUAD -> VehicleType.QUAD
    ApiVehicleType.SCOOTER -> VehicleType.SCOOTER
}

internal fun VehicleType.toApi(): ApiVehicleType = when (this) {
    VehicleType.MOTORCYCLE -> ApiVehicleType.MOTORCYCLE
    VehicleType.CAR -> ApiVehicleType.CAR
    VehicleType.BICYCLE -> ApiVehicleType.BICYCLE
    VehicleType.E_BIKE -> ApiVehicleType.E_BIKE
    VehicleType.QUAD -> ApiVehicleType.QUAD
    VehicleType.SCOOTER -> ApiVehicleType.SCOOTER
}

internal fun MaintenanceTaskStatus.toDomain(): TaskStatus = when (this) {
    MaintenanceTaskStatus.OVERDUE -> TaskStatus.OVERDUE
    MaintenanceTaskStatus.DUE_SOON -> TaskStatus.DUE_SOON
    MaintenanceTaskStatus.OK -> TaskStatus.OK
    MaintenanceTaskStatus.DONE -> TaskStatus.DONE
}

internal fun VehicleResponse.toDomain() = Vehicle(
    id = id,
    name = name,
    type = type.toDomain(),
    make = make,
    model = model,
    modelYear = modelYear,
    firstRegistrationDate = firstRegistrationDate,
    currentMileage = currentMileage,
)

internal fun MaintenanceTaskResponse.toDomain() = MaintenanceTask(
    id = id,
    vehicleId = vehicleId,
    name = name,
    oneTime = oneTime,
    active = active,
    status = status.toDomain(),
    progress = progress,
    kmRemaining = kmRemaining,
    daysRemaining = daysRemaining,
    lastServiceMileage = lastServiceMileage,
    lastServiceDate = lastServiceDate,
    remainingFraction = remainingFraction,
    description = description,
    intervalKm = intervalKm,
    intervalMonths = intervalMonths,
    firstDueKm = firstDueKm,
    firstDueMonths = firstDueMonths,
)

internal fun ProfileResponse.toDomain() = Profile(id, displayName)

internal fun AppConfigResponse.toDomain() = ServerConfig(
    mode = when (mode) {
        OperatingMode.PORTAL -> ServerMode.PORTAL
        OperatingMode.HOUSEHOLD -> ServerMode.HOUSEHOLD
    },
    // Opened in the browser: anything but http(s) (javascript:, file:, intent:, …) counts as not configured.
    websiteUrl = websiteUrl?.takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) }?.toString(),
)
