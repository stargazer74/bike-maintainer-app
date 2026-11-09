package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.VehicleRequest
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleSummary
import de.bikemaintainer.app.domain.dueCount
import de.bikemaintainer.app.domain.overallStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Vehicle data; implemented by [ApiVehicleRepository], faked in UI tests. */
interface VehicleRepository {
    suspend fun vehicles(): Outcome<List<Vehicle>>

    suspend fun vehicle(id: Long): Outcome<Vehicle>

    /** All vehicles with their overall task status; the first failing task list fails the whole call. */
    suspend fun summaries(): Outcome<List<VehicleSummary>>

    suspend fun updateMileage(vehicle: Vehicle, newMileage: Int): Outcome<Vehicle>
}

class ApiVehicleRepository(private val provider: ApiProvider) : VehicleRepository {
    override suspend fun vehicles(): Outcome<List<Vehicle>> =
        provider.callStored { it.vehicles.listVehicles() }.map { list -> list.map { it.toDomain() } }

    override suspend fun vehicle(id: Long): Outcome<Vehicle> =
        provider.callStored { it.vehicles.getVehicle(id) }.map { it.toDomain() }

    /** PUT replaces the whole vehicle, so all other fields are taken from [vehicle] (the last loaded state). */
    override suspend fun updateMileage(vehicle: Vehicle, newMileage: Int): Outcome<Vehicle> {
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

    override suspend fun summaries(): Outcome<List<VehicleSummary>> {
        val list = when (val result = vehicles()) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        val taskResults = coroutineScope {
            list.map { vehicle ->
                async { provider.callStored { it.tasks.listMaintenanceTasks(vehicle.id) } }
            }.map { it.await() }
        }
        val summaries = list.zip(taskResults).map { (vehicle, result) ->
            when (result) {
                is Outcome.Failure -> return result
                is Outcome.Success -> {
                    val tasks = result.value.map { it.toDomain() }
                    VehicleSummary(vehicle, tasks.overallStatus(), tasks.dueCount())
                }
            }
        }
        return Outcome.Success(summaries)
    }
}
