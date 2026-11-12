package de.bikemaintainer.app.domain

import java.time.LocalDate

/** What the vehicle form sends; the same fields as in the web app. */
data class VehicleInput(
    val name: String,
    val type: VehicleType,
    val make: String?,
    val model: String?,
    val modelYear: Int,
    val firstRegistrationDate: LocalDate,
    val currentMileage: Int,
)

/** A field of the vehicle form that is not valid. */
enum class VehicleField { NAME, MAKE, MODEL, MODEL_YEAR, MILEAGE }

/** The vehicle form as typed: numbers stay text until saved, so half-typed input is kept. */
data class VehicleDraft(
    val name: String = "",
    val type: VehicleType = VehicleType.MOTORCYCLE,
    val make: String = "",
    val model: String = "",
    val modelYear: String = "",
    val firstRegistrationDate: LocalDate,
    val currentMileage: String = "0",
    /** Model year of the vehicle being edited; it stays valid even outside the range of new input (see below). */
    val storedModelYear: Int? = null,
) {
    /**
     * Fields that are not valid, checked like the web form: lengths, model year 1900 to next year, any first
     * registration. A model year the API accepted elsewhere (up to 2100, e.g. from an import) is kept, so the
     * vehicle can still be saved.
     */
    fun invalidFields(today: LocalDate): Set<VehicleField> = buildSet {
        if (name.isBlank() || name.trim().length > MAX_NAME) add(VehicleField.NAME)
        if (make.trim().length > MAX_MAKE_MODEL) add(VehicleField.MAKE)
        if (model.trim().length > MAX_MAKE_MODEL) add(VehicleField.MODEL)
        val year = modelYear.trim().toIntOrNull()
        if (year == null || (year !in MIN_YEAR..today.year + 1 && year != storedModelYear)) add(VehicleField.MODEL_YEAR)
        if (parseMileage(currentMileage) == null) add(VehicleField.MILEAGE)
    }

    /** The input to save, `null` while a field is invalid. Blank make and model are not sent. */
    fun toInput(today: LocalDate): VehicleInput? {
        if (invalidFields(today).isNotEmpty()) return null
        return VehicleInput(
            name = name.trim(),
            type = type,
            make = make.trim().ifEmpty { null },
            model = model.trim().ifEmpty { null },
            modelYear = modelYear.trim().toInt(),
            firstRegistrationDate = firstRegistrationDate,
            currentMileage = parseMileage(currentMileage)!!,
        )
    }

    companion object {
        const val MAX_NAME = 255
        const val MAX_MAKE_MODEL = 100
        const val MIN_YEAR = 1900

        /** An empty form: model year and first registration in the current year, like the web app. */
        fun empty(today: LocalDate) = VehicleDraft(
            modelYear = today.year.toString(),
            firstRegistrationDate = LocalDate.of(today.year, 1, 1),
        )

        fun of(vehicle: Vehicle) = VehicleDraft(
            name = vehicle.name,
            type = vehicle.type,
            make = vehicle.make.orEmpty(),
            model = vehicle.model.orEmpty(),
            modelYear = vehicle.modelYear.toString(),
            firstRegistrationDate = vehicle.firstRegistrationDate,
            currentMileage = vehicle.currentMileage.toString(),
            storedModelYear = vehicle.modelYear,
        )
    }
}
