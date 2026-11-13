package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.MaintenanceLogRequest
import de.bikemaintainer.app.api.models.MaintenanceTaskRequest
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.data.storage.OfflineStore
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Measurements
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskInput
import java.time.LocalDate

/** Maintenance tasks and logs; implemented by [ApiMaintenanceRepository], faked in UI tests. */
interface MaintenanceRepository {
    suspend fun tasks(vehicleId: Long): Outcome<List<MaintenanceTask>>

    suspend fun task(vehicleId: Long, taskId: Long): Outcome<MaintenanceTask>

    suspend fun createTask(vehicleId: Long, input: TaskInput): Outcome<MaintenanceTask>

    /** PUT replaces the whole task: [input] carries every field, including whether it is active. */
    suspend fun updateTask(vehicleId: Long, taskId: Long, input: TaskInput): Outcome<MaintenanceTask>

    suspend fun logMaintenance(
        vehicleId: Long,
        performedAt: LocalDate,
        mileage: Int,
        taskIds: Set<Long>,
        notes: String?,
        measurements: Measurements? = null,
    ): Outcome<Unit>
}

/** Without a connection, the task lists come from [offline] (see [callCached]); single tasks are edited online only. */
class ApiMaintenanceRepository(
    private val provider: ApiProvider,
    private val offline: OfflineStore = OfflineStore.None,
) : MaintenanceRepository {
    override suspend fun tasks(vehicleId: Long): Outcome<List<MaintenanceTask>> =
        provider.callCached(offline, OfflineKeys.tasks(vehicleId), TASKS) { it.tasks.listMaintenanceTasks(vehicleId) }
            .map { list -> list.map { it.toDomain() } }

    override suspend fun task(vehicleId: Long, taskId: Long): Outcome<MaintenanceTask> =
        provider.callStored { it.tasks.getMaintenanceTask(vehicleId, taskId) }.map { it.toDomain() }

    override suspend fun createTask(vehicleId: Long, input: TaskInput): Outcome<MaintenanceTask> =
        provider.callStored { it.tasks.createMaintenanceTask(vehicleId, input.toRequest()) }.map { it.toDomain() }

    override suspend fun updateTask(vehicleId: Long, taskId: Long, input: TaskInput): Outcome<MaintenanceTask> =
        provider.callStored { it.tasks.updateMaintenanceTask(vehicleId, taskId, input.toRequest()) }.map { it.toDomain() }

    private fun TaskInput.toRequest() = MaintenanceTaskRequest(
        name = name,
        description = description,
        intervalKm = intervalKm,
        intervalMonths = intervalMonths,
        firstDueKm = firstDueKm,
        firstDueMonths = firstDueMonths,
        oneTime = oneTime,
        active = active,
    )

    /**
     * A 2xx whose body cannot be read (Http(-1)) means the POST reached the server and the log exists;
     * reporting a failure would invite a retry that creates a duplicate.
     */
    private fun Outcome<Unit>.unreadableCreatedAsSaved(): Outcome<Unit> =
        if (this is Outcome.Failure && error == AppError.Http(-1, null)) Outcome.Success(Unit) else this

    /** Blank [notes] are not sent (the spec requires at least one character); empty [measurements] neither. */
    override suspend fun logMaintenance(
        vehicleId: Long,
        performedAt: LocalDate,
        mileage: Int,
        taskIds: Set<Long>,
        notes: String?,
        measurements: Measurements?,
    ): Outcome<Unit> {
        val request = MaintenanceLogRequest(
            performedAt = performedAt,
            mileageAtPerformed = mileage,
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            performedTaskIds = taskIds,
            measurements = measurements?.takeUnless { it.isEmpty }?.toRequest(),
        )
        return provider.callStored { it.logs.createMaintenanceLog(vehicleId, request) }.map { }.unreadableCreatedAsSaved()
    }
}
