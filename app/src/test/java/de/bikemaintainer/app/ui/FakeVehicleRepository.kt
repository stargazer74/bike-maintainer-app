package de.bikemaintainer.app.ui

import de.bikemaintainer.app.data.repository.MaintenanceRepository
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Measurements
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleSummary
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred

/** Configurable [VehicleRepository] for ViewModel tests: set a `...Result`, read the `...Calls`, hold with a `...Gate`. */
class FakeVehicleRepository : VehicleRepository {
    var vehiclesResult: Outcome<List<Vehicle>> = Outcome.Success(emptyList())
    var vehiclesGate: CompletableDeferred<Unit>? = null
    var vehiclesCalls = 0

    var vehicleResult: Outcome<Vehicle>? = null
    var vehicleGate: CompletableDeferred<Unit>? = null
    val vehicleCalls = mutableListOf<Long>()

    var summariesResult: Outcome<List<VehicleSummary>> = Outcome.Success(emptyList())
    var summariesGate: CompletableDeferred<Unit>? = null
    var summariesCalls = 0

    var updateMileageResult: Outcome<Vehicle>? = null
    var updateMileageGate: CompletableDeferred<Unit>? = null
    val updateMileageCalls = mutableListOf<Pair<Vehicle, Int>>()

    override suspend fun vehicles(): Outcome<List<Vehicle>> {
        vehiclesCalls++
        vehiclesGate?.await()
        return vehiclesResult
    }

    override suspend fun vehicle(id: Long): Outcome<Vehicle> {
        vehicleCalls += id
        vehicleGate?.await()
        return vehicleResult ?: error("vehicleResult not set")
    }

    override suspend fun summaries(): Outcome<List<VehicleSummary>> {
        summariesCalls++
        summariesGate?.await()
        return summariesResult
    }

    override suspend fun updateMileage(vehicle: Vehicle, newMileage: Int): Outcome<Vehicle> {
        updateMileageCalls += vehicle to newMileage
        updateMileageGate?.await()
        return updateMileageResult ?: error("updateMileageResult not set")
    }
}

/** Configurable [MaintenanceRepository], same style as [FakeVehicleRepository]. */
class FakeMaintenanceRepository : MaintenanceRepository {
    data class LogCall(
        val vehicleId: Long,
        val performedAt: LocalDate,
        val mileage: Int,
        val taskIds: Set<Long>,
        val notes: String?,
        val measurements: Measurements? = null,
    )

    var tasksResult: Outcome<List<MaintenanceTask>> = Outcome.Success(emptyList())
    var tasksGate: CompletableDeferred<Unit>? = null
    val tasksCalls = mutableListOf<Long>()

    var logResult: Outcome<Unit> = Outcome.Success(Unit)
    var logGate: CompletableDeferred<Unit>? = null
    val logCalls = mutableListOf<LogCall>()

    override suspend fun tasks(vehicleId: Long): Outcome<List<MaintenanceTask>> {
        tasksCalls += vehicleId
        tasksGate?.await()
        return tasksResult
    }

    override suspend fun logMaintenance(
        vehicleId: Long,
        performedAt: LocalDate,
        mileage: Int,
        taskIds: Set<Long>,
        notes: String?,
        measurements: Measurements?,
    ): Outcome<Unit> {
        logCalls += LogCall(vehicleId, performedAt, mileage, taskIds, notes, measurements)
        logGate?.await()
        return logResult
    }
}
