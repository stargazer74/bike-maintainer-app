package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.MaintenanceLogRequest
import de.bikemaintainer.app.api.models.MaintenanceTaskRequest
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.data.storage.OfflineStore
import de.bikemaintainer.app.data.storage.OutboxEntry
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Measurements
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Recorded
import de.bikemaintainer.app.domain.TaskInput
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Maintenance tasks and logs; implemented by [ApiMaintenanceRepository], faked in UI tests. */
interface MaintenanceRepository {
    suspend fun tasks(vehicleId: Long): Outcome<List<MaintenanceTask>>

    suspend fun task(vehicleId: Long, taskId: Long): Outcome<MaintenanceTask>

    suspend fun createTask(vehicleId: Long, input: TaskInput): Outcome<MaintenanceTask>

    /** PUT replaces the whole task: [input] carries every field, including whether it is active. */
    suspend fun updateTask(vehicleId: Long, taskId: Long, input: TaskInput): Outcome<MaintenanceTask>

    /**
     * [clientEntryId] identifies this recording: the same id twice never creates two logs (keep it across retries of
     * the same form). Without a connection the log is kept on the device and sent later ([Recorded.QUEUED]); then
     * the vehicle's mileage is raised to [raiseMileageTo] after it, if given.
     */
    suspend fun logMaintenance(
        vehicleId: Long,
        performedAt: LocalDate,
        mileage: Int,
        taskIds: Set<Long>,
        notes: String?,
        measurements: Measurements? = null,
        clientEntryId: UUID = UUID.randomUUID(),
        raiseMileageTo: Int? = null,
    ): Outcome<Recorded>
}

/** Without a connection, the task lists come from [offline] (see [callCached]); single tasks are edited online only. */
class ApiMaintenanceRepository(
    private val provider: ApiProvider,
    private val offline: OfflineStore = OfflineStore.None,
    /** Where recordings go without a connection; null: they fail with [AppError.Network] (tests). */
    private val pending: PendingRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
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

    private suspend fun queued(vehicleId: Long, request: MaintenanceLogRequest, raiseMileageTo: Int?): Outcome<Recorded> {
        val entry = OutboxEntry(
            id = request.clientEntryId.toString(),
            vehicleId = vehicleId,
            createdAt = clock.millis(),
            log = request,
            raiseMileageTo = raiseMileageTo,
        )
        return if (pending!!.enqueue(entry)) Outcome.Success(Recorded.QUEUED) else Outcome.Failure(AppError.Storage)
    }

    /** Blank [notes] are not sent (the spec requires at least one character); empty [measurements] neither. */
    override suspend fun logMaintenance(
        vehicleId: Long,
        performedAt: LocalDate,
        mileage: Int,
        taskIds: Set<Long>,
        notes: String?,
        measurements: Measurements?,
        clientEntryId: UUID,
        raiseMileageTo: Int?,
    ): Outcome<Recorded> {
        val request = MaintenanceLogRequest(
            clientEntryId = clientEntryId,
            performedAt = performedAt,
            mileageAtPerformed = mileage,
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            performedTaskIds = taskIds,
            measurements = measurements?.takeUnless { it.isEmpty }?.toRequest(),
        )
        val result = provider.callStored { it.logs.createMaintenanceLog(vehicleId, request) }.map { }
            .unreadableCreatedAsSaved()
        return when {
            result is Outcome.Success -> Outcome.Success(Recorded.SAVED)
            result is Outcome.Failure && result.error == AppError.Network && pending != null ->
                queued(vehicleId, request, raiseMileageTo)
            else -> result as Outcome.Failure
        }
    }
}
