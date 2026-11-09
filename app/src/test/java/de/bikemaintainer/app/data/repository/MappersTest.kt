package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.MaintenanceTaskResponse
import de.bikemaintainer.app.api.models.MaintenanceTaskStatus
import de.bikemaintainer.app.api.models.VehicleResponse
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.domain.VehicleType
import java.time.LocalDate
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Test
import de.bikemaintainer.app.api.models.VehicleType as ApiVehicleType

class MappersTest {
    private val now = OffsetDateTime.parse("2026-01-01T00:00:00Z")

    @Test
    fun mapsVehicleAndTaskDtos() {
        val vehicle = VehicleResponse(
            id = 3, name = "Tenere", type = ApiVehicleType.E_BIKE, modelYear = 2021,
            firstRegistrationDate = LocalDate.of(2021, 5, 4), currentMileage = 23000,
            createdAt = now, updatedAt = now, make = "Yamaha", model = null,
        )
        assertEquals(
            Vehicle(3, "Tenere", VehicleType.E_BIKE, "Yamaha", null, 2021, LocalDate.of(2021, 5, 4), 23000),
            vehicle.toDomain(),
        )

        val full = MaintenanceTaskResponse(
            id = 1, vehicleId = 3, name = "Oil", oneTime = false, active = true,
            status = MaintenanceTaskStatus.DUE_SOON, progress = 0.8, kmRemaining = 200, daysRemaining = 12,
            lastServiceMileage = 20000, lastServiceDate = LocalDate.of(2026, 1, 2),
        )
        assertEquals(
            MaintenanceTask(1, 3, "Oil", false, true, TaskStatus.DUE_SOON, 0.8, 200, 12, 20000, LocalDate.of(2026, 1, 2)),
            full.toDomain(),
        )
        val sparse = MaintenanceTaskResponse(
            id = 2, vehicleId = 3, name = "Chain", oneTime = true, active = false, status = MaintenanceTaskStatus.DONE,
        )
        assertEquals(
            MaintenanceTask(2, 3, "Chain", true, false, TaskStatus.DONE, null, null, null, null, null),
            sparse.toDomain(),
        )
        MaintenanceTaskStatus.entries.forEach { assertEquals(it.name, it.toDomain().name) }
        ApiVehicleType.entries.forEach { assertEquals(it.name, it.toDomain().name) }
    }
}
