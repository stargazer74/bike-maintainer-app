package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.MaintenanceLogRequest
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import java.time.LocalDate

/** Maintenance tasks and logs; implemented by [ApiMaintenanceRepository], faked in UI tests. */
interface MaintenanceRepository {
    suspend fun tasks(vehicleId: Long): Outcome<List<MaintenanceTask>>

    suspend fun logMaintenance(
        vehicleId: Long,
        performedAt: LocalDate,
        mileage: Int,
        taskIds: Set<Long>,
        notes: String?,
    ): Outcome<Unit>
}

class ApiMaintenanceRepository(private val provider: ApiProvider) : MaintenanceRepository {
    override suspend fun tasks(vehicleId: Long): Outcome<List<MaintenanceTask>> =
        provider.callStored { it.tasks.listMaintenanceTasks(vehicleId) }.map { list -> list.map { it.toDomain() } }

    /**
     * A 2xx whose body cannot be read (Http(-1)) means the POST reached the server and the log exists;
     * reporting a failure would invite a retry that creates a duplicate.
     */
    private fun Outcome<Unit>.unreadableCreatedAsSaved(): Outcome<Unit> =
        if (this is Outcome.Failure && error == AppError.Http(-1, null)) Outcome.Success(Unit) else this

    /** Blank [notes] are not sent (the spec requires at least one character). */
    override suspend fun logMaintenance(
        vehicleId: Long,
        performedAt: LocalDate,
        mileage: Int,
        taskIds: Set<Long>,
        notes: String?,
    ): Outcome<Unit> {
        val request = MaintenanceLogRequest(
            performedAt = performedAt,
            mileageAtPerformed = mileage,
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            performedTaskIds = taskIds,
        )
        return provider.callStored { it.logs.createMaintenanceLog(vehicleId, request) }.map { }.unreadableCreatedAsSaved()
    }
}
