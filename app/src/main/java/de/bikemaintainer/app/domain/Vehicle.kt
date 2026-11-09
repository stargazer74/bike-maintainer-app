package de.bikemaintainer.app.domain

import java.time.LocalDate

enum class VehicleType { MOTORCYCLE, CAR, BICYCLE, E_BIKE, QUAD, SCOOTER }

data class Vehicle(
    val id: Long,
    val name: String,
    val type: VehicleType,
    val make: String?,
    val model: String?,
    val modelYear: Int,
    val firstRegistrationDate: LocalDate,
    val currentMileage: Int,
)
