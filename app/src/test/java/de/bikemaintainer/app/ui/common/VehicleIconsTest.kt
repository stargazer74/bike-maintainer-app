package de.bikemaintainer.app.ui.common

import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleIconsTest {
    @Test fun everyTypeHasItsOwnSymbol() {
        val icons = VehicleType.entries.map { it.iconRes() }
        assertEquals(VehicleType.entries.size, icons.toSet().size)
    }

    @Test fun motorcycleUsesTheTwoWheeler() {
        assertEquals(R.drawable.ic_vehicle_motorcycle, VehicleType.MOTORCYCLE.iconRes())
    }
}
