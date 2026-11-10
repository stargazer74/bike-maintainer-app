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

/** Page [page] of the valve clearance sheet for 8 cylinders with 3 valves (3 pages); every measured value is "0.1". */
internal fun valveSheetPage(page: Int, vehicleId: Long = 3, cylinders: Int = 8) = ScannedSheet(
    SheetCode(1, MeasurementTemplate.VALVE_CLEARANCE, vehicleId, cylinders, 3, page, ScanLayout.pageCount(MeasurementTemplate.VALVE_CLEARANCE, cylinders, 3)),
    ScanLayout.pages(MeasurementTemplate.VALVE_CLEARANCE, cylinders, 3)[page - 1].map { field ->
        FieldReading(field, value = if (field.id.endsWith("measured")) "0.1" else "", crop = CROP)
    },
)
