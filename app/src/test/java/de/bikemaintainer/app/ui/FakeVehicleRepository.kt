package de.bikemaintainer.app.ui

import de.bikemaintainer.app.data.repository.MaintenanceRepository
import de.bikemaintainer.app.data.repository.VehicleRepository
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Measurements
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskInput
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleInput
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

    var createVehicleResult: Outcome<Vehicle>? = null
    var createVehicleGate: CompletableDeferred<Unit>? = null
    val createVehicleCalls = mutableListOf<VehicleInput>()

    var updateVehicleResult: Outcome<Vehicle>? = null
    val updateVehicleCalls = mutableListOf<Pair<Long, VehicleInput>>()

    var deleteVehicleResult: Outcome<Unit> = Outcome.Success(Unit)
    var deleteVehicleGate: CompletableDeferred<Unit>? = null
    val deleteVehicleCalls = mutableListOf<Long>()

    override suspend fun createVehicle(input: VehicleInput): Outcome<Vehicle> {
        createVehicleCalls += input
        createVehicleGate?.await()
        return createVehicleResult ?: error("createVehicleResult not set")
    }

    override suspend fun updateVehicle(id: Long, input: VehicleInput): Outcome<Vehicle> {
        updateVehicleCalls += id to input
        return updateVehicleResult ?: error("updateVehicleResult not set")
    }

    override suspend fun deleteVehicle(id: Long): Outcome<Unit> {
        deleteVehicleCalls += id
        deleteVehicleGate?.await()
        return deleteVehicleResult
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

    var taskResult: Outcome<MaintenanceTask>? = null
    val taskCalls = mutableListOf<Pair<Long, Long>>()

    var createTaskResult: Outcome<MaintenanceTask>? = null
    val createTaskCalls = mutableListOf<Pair<Long, TaskInput>>()

    var updateTaskResult: Outcome<MaintenanceTask>? = null
    val updateTaskCalls = mutableListOf<Triple<Long, Long, TaskInput>>()

    override suspend fun task(vehicleId: Long, taskId: Long): Outcome<MaintenanceTask> {
        taskCalls += vehicleId to taskId
        return taskResult ?: error("taskResult not set")
    }

    override suspend fun createTask(vehicleId: Long, input: TaskInput): Outcome<MaintenanceTask> {
        createTaskCalls += vehicleId to input
        return createTaskResult ?: error("createTaskResult not set")
    }

    override suspend fun updateTask(vehicleId: Long, taskId: Long, input: TaskInput): Outcome<MaintenanceTask> {
        updateTaskCalls += Triple(vehicleId, taskId, input)
        return updateTaskResult ?: error("updateTaskResult not set")
    }
}
