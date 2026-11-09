package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.VehicleRequest
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Vehicle

class VehicleRepository(private val provider: ApiProvider) {
    suspend fun vehicles(): Outcome<List<Vehicle>> =
        provider.callStored { it.vehicles.listVehicles() }.map { list -> list.map { it.toDomain() } }

    suspend fun vehicle(id: Long): Outcome<Vehicle> =
        provider.callStored { it.vehicles.getVehicle(id) }.map { it.toDomain() }

    /** PUT replaces the whole vehicle, so all other fields are taken from [vehicle] (the last loaded state). */
    suspend fun updateMileage(vehicle: Vehicle, newMileage: Int): Outcome<Vehicle> {
        val request = VehicleRequest(
            name = vehicle.name,
            type = vehicle.type.toApi(),
            modelYear = vehicle.modelYear,
            firstRegistrationDate = vehicle.firstRegistrationDate,
            currentMileage = newMileage,
            make = vehicle.make,
            model = vehicle.model,
        )
        return provider.callStored { it.vehicles.updateVehicle(vehicle.id, request) }.map { it.toDomain() }
    }
}
