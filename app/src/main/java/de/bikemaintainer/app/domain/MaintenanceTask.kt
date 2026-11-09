package de.bikemaintainer.app.domain

import java.time.LocalDate

enum class TaskStatus { OVERDUE, DUE_SOON, OK, DONE }

data class MaintenanceTask(
    val id: Long,
    val vehicleId: Long,
    val name: String,
    val oneTime: Boolean,
    val active: Boolean,
    val status: TaskStatus,
    val progress: Double?,
    val kmRemaining: Int?,
    val daysRemaining: Int?,
    val lastServiceMileage: Int?,
    val lastServiceDate: LocalDate?,
)
