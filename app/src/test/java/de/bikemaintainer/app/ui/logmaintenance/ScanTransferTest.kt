package de.bikemaintainer.app.ui.logmaintenance

import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.Specification
import de.bikemaintainer.app.domain.ValveKind
import de.bikemaintainer.app.domain.ValveMeasurement
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTransferTest {
    private val spec = Specification(cylinders = 1, valvesPerCylinder = 2, chainWearLinks = 20)
    private val latest = listOf(ValveMeasurement(1, ValveKind.INTAKE, 1, null, BigDecimal("2.55"), null))

    @Test
    fun valves_newTemplateTakesTheLayoutOfTheSheetAndKeepsPrefilledOldShims() {
        val scan = ScanTransfer(
            MeasurementTemplate.VALVE_CLEARANCE, cylinders = 2, valvesPerCylinder = 2,
            values = mapOf(
                "valves.1.INTAKE.1.measured" to "0,12",
                "valves.1.INTAKE.1.shimOld" to "",
                "valves.2.EXHAUST.1.shimNew" to "2,45",
            ),
        )
        val valves = MeasurementDrafts().withScan(scan, spec, latest, ',').valves!!
        assertEquals(4, valves.size)
        assertEquals("0,12", valves[0].measured)
        assertEquals("2,55", valves[0].shimOld) // empty on the sheet: the prefill stays
        assertEquals(ValveDraft(2, ValveKind.EXHAUST, 1, shimNew = "2,45"), valves[3])
    }

    @Test
    fun valves_existingRowsKeepTheirInputWhereTheSheetIsEmpty() {
        val drafts = MeasurementDrafts(valves = listOf(ValveDraft(1, ValveKind.INTAKE, 1, measured = "0,10"), ValveDraft(1, ValveKind.EXHAUST, 1)))
        val scan = ScanTransfer(MeasurementTemplate.VALVE_CLEARANCE, 1, 2, values = mapOf("valves.1.EXHAUST.1.measured" to "0,22"))
        val valves = drafts.withScan(scan, spec, null, ',').valves!!
        assertEquals(listOf("0,10", "0,22"), valves.map { it.measured })
    }

    @Test
    fun brakesChainAndTires_addedAndFilled() {
        val brakes = MeasurementDrafts().withScan(
            ScanTransfer(MeasurementTemplate.BRAKES, values = mapOf("brakes.padFront" to "4,5", "brakes.discRear" to "4,80")),
            spec, null, ',',
        ).brakes!!
        assertEquals(BrakeDraft(padFront = "4,5", discRear = "4,80"), brakes)

        val chain = MeasurementDrafts(chain = ChainDraft(cleaned = true, lubricated = true)).withScan(
            ScanTransfer(MeasurementTemplate.CHAIN, values = mapOf("chain.slack" to "30", "chain.wearLength" to ""), checks = mapOf("chain.lubricated" to false)),
            spec, null, ',',
        ).chain!!
        assertEquals("30", chain.slack)
        assertTrue(chain.cleaned) // not on the sheet: unchanged
        assertFalse(chain.lubricated) // the sheet wins

        val newChain = MeasurementDrafts().withScan(ScanTransfer(MeasurementTemplate.CHAIN), spec, null, ',').chain!!
        assertEquals("20", newChain.wearLinks)

        val tires = MeasurementDrafts().withScan(
            ScanTransfer(
                MeasurementTemplate.TIRES,
                values = mapOf(
                    "tires.pressureFront" to "2,5", "tires.pressureRear" to "2,9", "tires.treadFront" to "3,1",
                    "tires.treadRear" to "2,0", "tires.dotFront" to "2324", "tires.dotRear" to "0125",
                ),
            ),
            spec, null, ',',
        ).tires!!
        assertEquals(TireDraft("2,5", "2,9", "3,1", "2,0", "2324", "0125"), tires)
    }
}
