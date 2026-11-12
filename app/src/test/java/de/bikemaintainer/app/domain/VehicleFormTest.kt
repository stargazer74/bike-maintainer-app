package de.bikemaintainer.app.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleFormTest {
    private val today = LocalDate.of(2026, 11, 12)
    private val valid = VehicleDraft(
        name = " Africa Twin ",
        type = VehicleType.MOTORCYCLE,
        make = " Honda ",
        model = "",
        modelYear = "2020",
        firstRegistrationDate = LocalDate.of(2020, 5, 1),
        currentMileage = "12345",
    )

    @Test fun validDraftIsTrimmedAndBlankOptionalsAreLeftOut() {
        assertEquals(
            VehicleInput("Africa Twin", VehicleType.MOTORCYCLE, "Honda", null, 2020, LocalDate.of(2020, 5, 1), 12345),
            valid.toInput(today),
        )
    }

    @Test fun emptyFormStartsInTheCurrentYear() {
        val empty = VehicleDraft.empty(today)
        assertEquals("2026", empty.modelYear)
        assertEquals(LocalDate.of(2026, 1, 1), empty.firstRegistrationDate)
        assertEquals(setOf(VehicleField.NAME), empty.invalidFields(today))
    }

    @Test fun everyFieldIsChecked() {
        val bad = valid.copy(
            name = " ",
            make = "x".repeat(101),
            model = "y".repeat(101),
            modelYear = "1899",
            firstRegistrationDate = today.plusDays(1),
            currentMileage = "12.345",
        )
        assertEquals(VehicleField.entries.toSet(), bad.invalidFields(today))
        assertNull(bad.toInput(today))
    }

    @Test fun modelYearMayBeNextYear() {
        assertTrue(valid.copy(modelYear = "2027").invalidFields(today).isEmpty())
        assertEquals(setOf(VehicleField.MODEL_YEAR), valid.copy(modelYear = "2028").invalidFields(today))
        assertEquals(setOf(VehicleField.MODEL_YEAR), valid.copy(modelYear = "").invalidFields(today))
    }

    @Test fun nameMayHave255Characters() {
        assertTrue(valid.copy(name = "n".repeat(255)).invalidFields(today).isEmpty())
        assertEquals(setOf(VehicleField.NAME), valid.copy(name = "n".repeat(256)).invalidFields(today))
    }

    @Test fun draftOfAVehicleShowsItsValues() {
        val vehicle = Vehicle(7, "Golf", VehicleType.CAR, null, "GTI", 2015, LocalDate.of(2015, 3, 1), 99000)
        val draft = VehicleDraft.of(vehicle)
        assertEquals("", draft.make)
        assertEquals("99000", draft.currentMileage)
        assertEquals(VehicleInput("Golf", VehicleType.CAR, null, "GTI", 2015, LocalDate.of(2015, 3, 1), 99000), draft.toInput(today))
    }
}
