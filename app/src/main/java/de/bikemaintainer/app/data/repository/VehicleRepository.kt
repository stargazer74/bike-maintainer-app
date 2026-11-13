package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.MaintenanceTaskResponse
import de.bikemaintainer.app.api.models.VehicleRequest
import de.bikemaintainer.app.api.models.VehicleResponse
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.data.storage.OfflineStore
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleInput
import de.bikemaintainer.app.domain.VehicleSummary
import de.bikemaintainer.app.domain.dueCount
import de.bikemaintainer.app.domain.olderOf
import de.bikemaintainer.app.domain.overallStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer

/** Task lists as stored offline; shared with [ApiMaintenanceRepository]. */
internal val TASKS = ListSerializer(MaintenanceTaskResponse.serializer())

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

/**
 * Without a connection, answers come from [offline] (see [callCached]). Loading the list also stores every vehicle and
 * its tasks, so each vehicle opens offline, and drops the stored data of vehicles that are gone.
 */
class ApiVehicleRepository(
    private val provider: ApiProvider,
    private val offline: OfflineStore = OfflineStore.None,
) : VehicleRepository {
    override suspend fun vehicles(): Outcome<List<Vehicle>> {
        val result = provider.callCached(offline, OfflineKeys.VEHICLES, ListSerializer(VehicleResponse.serializer())) {
            it.vehicles.listVehicles()
        }
        if (result is Outcome.Success && result.asOf == null) {
            result.value.forEach { offline.save(OfflineKeys.vehicle(it.id), VehicleResponse.serializer(), it) }
            val ids = result.value.map { it.id }.toSet()
            offline.retain { key -> OfflineKeys.vehicleIdOf(key)?.let { it in ids } ?: true }
        }
        return result.map { list -> list.map { it.toDomain() } }
    }

    override suspend fun vehicle(id: Long): Outcome<Vehicle> =
        provider.callCached(offline, OfflineKeys.vehicle(id), VehicleResponse.serializer()) {
            it.vehicles.getVehicle(id)
        }.map { it.toDomain() }

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

    override suspend fun deleteVehicle(id: Long): Outcome<Unit> {
        val result = provider.callStoredNoBody { it.vehicles.deleteVehicle(id) }
        if (result is Outcome.Success || (result is Outcome.Failure && result.error == AppError.NotFound)) {
            offline.retain { key -> OfflineKeys.vehicleIdOf(key) != id }
        }
        return result
    }

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
        val result = vehicles()
        val list = when (result) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        var asOf = (result as Outcome.Success).asOf
        val taskResults = coroutineScope {
            list.map { vehicle ->
                async {
                    provider.callCached(offline, OfflineKeys.tasks(vehicle.id), TASKS) {
                        it.tasks.listMaintenanceTasks(vehicle.id)
                    }
                }
            }.map { it.await() }
        }
        val summaries = list.zip(taskResults).map { (vehicle, taskResult) ->
            when (taskResult) {
                is Outcome.Failure -> return taskResult
                is Outcome.Success -> {
                    asOf = olderOf(asOf, taskResult.asOf)
                    val tasks = taskResult.value.map { it.toDomain() }
                    VehicleSummary(vehicle, tasks.overallStatus(), tasks.dueCount())
                }
            }
        }
        return Outcome.Success(summaries, asOf)
    }
}
