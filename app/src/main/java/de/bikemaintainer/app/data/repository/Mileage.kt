package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.MileageRequest
import de.bikemaintainer.app.api.models.VehicleRequest
import de.bikemaintainer.app.api.models.VehicleResponse
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome

private const val HTTP_METHOD_NOT_ALLOWED = 405

/**
 * Raises the vehicle's mileage, also on servers before 0.20.0 (app#72).
 *
 * The server's own endpoint `PUT …/mileage` only raises and never touches other fields. A server that predates it
 * answers 404 (or 405) although the vehicle exists – without this check the app took that for a deleted vehicle and
 * the mileage was lost. Then the vehicle is read and written back whole with the new mileage, right away (read and
 * write a moment apart, as when editing the vehicle): the old way, which is why a value below the current one is
 * refused here as well. Only a 404 of that read means the vehicle is gone.
 */
internal suspend fun ApiProvider.raiseMileage(vehicleId: Long, mileage: Int): Outcome<VehicleResponse> {
    val result = callStored { it.vehicles.updateMileage(vehicleId, MileageRequest(mileage)) }
    if (result !is Outcome.Failure || !result.error.isMissingEndpoint()) return result
    val current = when (val read = callStored { it.vehicles.getVehicle(vehicleId) }) {
        is Outcome.Failure -> return read
        is Outcome.Success -> read.value
    }
    if (mileage < current.currentMileage) return Outcome.Failure(AppError.Http(409, "MILEAGE_BELOW_CURRENT"))
    if (mileage == current.currentMileage) return Outcome.Success(current)
    val request = VehicleRequest(
        name = current.name,
        type = current.type,
        modelYear = current.modelYear,
        firstRegistrationDate = current.firstRegistrationDate,
        currentMileage = mileage,
        make = current.make,
        model = current.model,
    )
    return callStored { it.vehicles.updateVehicle(vehicleId, request) }
}

private fun AppError.isMissingEndpoint(): Boolean =
    this == AppError.NotFound || (this is AppError.Http && status == HTTP_METHOD_NOT_ALLOWED)
