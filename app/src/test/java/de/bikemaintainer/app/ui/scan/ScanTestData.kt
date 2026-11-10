package de.bikemaintainer.app.ui.scan

import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.scan.FieldReading
import de.bikemaintainer.app.domain.scan.GrayImage
import de.bikemaintainer.app.domain.scan.ScanLayout
import de.bikemaintainer.app.domain.scan.ScannedSheet
import de.bikemaintainer.app.domain.scan.SheetCode

internal val CROP = GrayImage(30, 10)

internal fun chainSheet(vehicleId: Long = 3) = ScannedSheet(
    SheetCode(1, MeasurementTemplate.CHAIN, vehicleId, 0, 0, 1, 1),
    ScanLayout.pages(MeasurementTemplate.CHAIN).single().map { field ->
        when (field.id) {
            "chain.slack" -> FieldReading(field, value = "32.5", uncertain = true, crop = CROP)
            "chain.wearLength" -> FieldReading(field, value = "", crop = CROP)
            "chain.cleaned" -> FieldReading(field, checked = true, crop = CROP)
            else -> FieldReading(field, checked = false, crop = CROP)
        }
    },
)
