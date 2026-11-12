package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.VehicleRequest
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleInput
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

    suspend fun createVehicle(input: VehicleInput): Outcome<Vehicle>

    suspend fun updateVehicle(id: Long, input: VehicleInput): Outcome<Vehicle>

    /** Deletes the vehicle with all its tasks, logs and measurements; the server allows it also when read-only. */
    suspend fun deleteVehicle(id: Long): Outcome<Unit>
}

class ApiVehicleRepository(private val provider: ApiProvider) : VehicleRepository {
    override suspend fun vehicles(): Outcome<List<Vehicle>> =
        provider.callStored { it.vehicles.listVehicles() }.map { list -> list.map { it.toDomain() } }

    override suspend fun vehicle(id: Long): Outcome<Vehicle> =
        provider.callStored { it.vehicles.getVehicle(id) }.map { it.toDomain() }

    /** PUT replaces the whole vehicle, so all other fields are taken from [vehicle] (the last loaded state). */
    override suspend fun updateMileage(vehicle: Vehicle, newMileage: Int): Outcome<Vehicle> = updateVehicle(
        vehicle.id,
        VehicleInput(
            name = vehicle.name,
            type = vehicle.type,
            make = vehicle.make,
            model = vehicle.model,
            modelYear = vehicle.modelYear,
            firstRegistrationDate = vehicle.firstRegistrationDate,
            currentMileage = newMileage,
        ),
    )

    override suspend fun createVehicle(input: VehicleInput): Outcome<Vehicle> =
        provider.callStored { it.vehicles.createVehicle(input.toRequest()) }.map { it.toDomain() }

    override suspend fun updateVehicle(id: Long, input: VehicleInput): Outcome<Vehicle> =
        provider.callStored { it.vehicles.updateVehicle(id, input.toRequest()) }.map { it.toDomain() }

    override suspend fun deleteVehicle(id: Long): Outcome<Unit> =
        provider.callStoredNoBody { it.vehicles.deleteVehicle(id) }

    private fun VehicleInput.toRequest() = VehicleRequest(
        name = name,
        type = type.toApi(),
        modelYear = modelYear,
        firstRegistrationDate = firstRegistrationDate,
        currentMileage = currentMileage,
        make = make,
        model = model,
    )

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
