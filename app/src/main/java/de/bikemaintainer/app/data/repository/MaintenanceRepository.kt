package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.MaintenanceLogRequest
import de.bikemaintainer.app.data.network.ApiProvider
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.Outcome
import java.time.LocalDate

class MaintenanceRepository(private val provider: ApiProvider) {
    suspend fun tasks(vehicleId: Long): Outcome<List<MaintenanceTask>> =
        provider.callStored { it.tasks.listMaintenanceTasks(vehicleId) }.map { list -> list.map { it.toDomain() } }

    /** Blank [notes] are not sent (the spec requires at least one character). */
    suspend fun logMaintenance(
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
        return provider.callStored { it.logs.createMaintenanceLog(vehicleId, request) }.map { }
    }
}
