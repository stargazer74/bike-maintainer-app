package de.bikemaintainer.app.ui.logmaintenance

import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.Specification
import de.bikemaintainer.app.domain.ValveKind
import de.bikemaintainer.app.domain.ValveMeasurement
import kotlinx.serialization.Serializable

/**
 * The checked values of one photographed worksheet page, handed from the scan screen to the log form (Epic #120).
 * Only numbers and ticks – never the photo.
 *
 * @param values digit fields by id (e.g. `brakes.padFront`), as the user would type them; empty = not written
 * @param checks checkbox fields by id (e.g. `chain.cleaned`)
 */
@Serializable
data class ScanTransfer(
    val template: MeasurementTemplate,
    val cylinders: Int = 0,
    val valvesPerCylinder: Int = 0,
    val values: Map<String, String> = emptyMap(),
    val checks: Map<String, Boolean> = emptyMap(),
)

/**
 * The drafts with the scanned values taken over. A template that is not part of the log yet is added (prefilled like
 * when added by hand); the valve layout comes from the QR code. Fields left empty on the sheet keep what the form
 * already has (e.g. the old shim prefilled from the last measurement); ticks follow the sheet.
 */
fun MeasurementDrafts.withScan(
    scan: ScanTransfer,
    specification: Specification,
    latestValves: List<ValveMeasurement>?,
    decimalSeparator: Char,
): MeasurementDrafts {
    fun value(id: String, current: String) = scan.values[id]?.takeIf { it.isNotBlank() } ?: current
    fun check(id: String, current: Boolean) = scan.checks[id] ?: current
    return when (scan.template) {
        MeasurementTemplate.VALVE_CLEARANCE -> {
            val base = valves ?: newValveDrafts(specification, latestValves, decimalSeparator)
            val rows = valveLayout(scan.cylinders, scan.valvesPerCylinder, base)
            copy(
                valves = rows.map { row ->
                    val prefix = valveFieldPrefix(row.cylinder, row.kind, row.index)
                    row.copy(
                        measured = value(prefix + "measured", row.measured),
                        shimOld = value(prefix + "shimOld", row.shimOld),
                        shimNew = value(prefix + "shimNew", row.shimNew),
                    )
                },
            )
        }
        MeasurementTemplate.BRAKES -> {
            val b = brakes ?: BrakeDraft()
            copy(
                brakes = b.copy(
                    padFront = value("brakes.padFront", b.padFront),
                    padRear = value("brakes.padRear", b.padRear),
                    discFront = value("brakes.discFront", b.discFront),
                    discRear = value("brakes.discRear", b.discRear),
                ),
            )
        }
        MeasurementTemplate.CHAIN -> {
            val c = chain ?: newChainDraft(specification)
            copy(
                chain = c.copy(
                    slack = value("chain.slack", c.slack),
                    wearLength = value("chain.wearLength", c.wearLength),
                    cleaned = check("chain.cleaned", c.cleaned),
                    lubricated = check("chain.lubricated", c.lubricated),
                ),
            )
        }
        MeasurementTemplate.TIRES -> {
            val t = tires ?: TireDraft()
            copy(
                tires = t.copy(
                    pressureFront = value("tires.pressureFront", t.pressureFront),
                    pressureRear = value("tires.pressureRear", t.pressureRear),
                    treadFront = value("tires.treadFront", t.treadFront),
                    treadRear = value("tires.treadRear", t.treadRear),
                    dotFront = value("tires.dotFront", t.dotFront),
                    dotRear = value("tires.dotRear", t.dotRear),
                ),
            )
        }
    }
}

/** Field id prefix of a valve on the worksheet, e.g. `valves.2.EXHAUST.1.`. */
fun valveFieldPrefix(cylinder: Int, kind: ValveKind, index: Int) = "valves.$cylinder.${kind.name}.$index."
