package de.bikemaintainer.app.ui.logmaintenance

import de.bikemaintainer.app.domain.MeasurementStatus
import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.ShimInput
import de.bikemaintainer.app.domain.Specification
import de.bikemaintainer.app.domain.ValveKind
import de.bikemaintainer.app.domain.ValveMeasurement
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementDraftsTest {
    private fun keys(valves: List<ValveDraft>) = valves.map { Triple(it.cylinder, it.kind, it.index) }

    @Test fun layoutOrdersByCylinderIntakeFirst() {
        assertEquals(
            listOf(
                Triple(1, ValveKind.INTAKE, 1), Triple(1, ValveKind.INTAKE, 2), Triple(1, ValveKind.EXHAUST, 1),
                Triple(2, ValveKind.INTAKE, 1), Triple(2, ValveKind.INTAKE, 2), Triple(2, ValveKind.EXHAUST, 1),
            ),
            keys(valveLayout(2, 3)),
        )
        assertEquals(4, valveLayout(1, 4).size)
        assertEquals(EngineLayout(3, 2), valveLayout(3, 2).layout())
    }

    @Test fun changingTheLayoutKeepsValuesAtTheSamePosition() {
        val before = valveLayout(1, 2).map { it.copy(measured = "0,1${it.kind.ordinal}") }
        val after = valveLayout(2, 4, before)
        assertEquals(8, after.size)
        assertEquals("0,10", after.first { it.cylinder == 1 && it.kind == ValveKind.INTAKE && it.index == 1 }.measured)
        assertEquals("", after.first { it.cylinder == 2 }.measured)
    }

    @Test fun newValveDraftsUseVehicleLayoutAndInstalledShims() {
        val latest = listOf(
            ValveMeasurement(1, ValveKind.INTAKE, 1, BigDecimal("0.08"), BigDecimal("2.550"), BigDecimal("2.500")),
            ValveMeasurement(1, ValveKind.EXHAUST, 1, BigDecimal("0.2"), BigDecimal("2.600"), null),
        )
        val drafts = newValveDrafts(Specification(cylinders = 1, valvesPerCylinder = 2), latest, ',')
        assertEquals(listOf("2,5", "2,6"), drafts.map { it.shimOld })
        assertTrue(drafts.all { it.measured.isEmpty() && it.shimNew.isEmpty() })
    }

    @Test fun newValveDraftsFallBackToLatestThenDefault() {
        val latest = listOf(
            ValveMeasurement(1, ValveKind.INTAKE, 1, null, null, null),
            ValveMeasurement(1, ValveKind.EXHAUST, 1, null, null, null),
            ValveMeasurement(3, ValveKind.INTAKE, 1, null, null, null),
        )
        assertEquals(EngineLayout(3, 2), newValveDrafts(Specification(), latest, '.').layout())
        assertEquals(
            EngineLayout(DEFAULT_CYLINDERS, DEFAULT_VALVES_PER_CYLINDER),
            newValveDrafts(Specification(), null, '.').layout(),
        )
    }

    @Test fun chainDraftTakesLinksFromSpecification() {
        assertEquals("20", newChainDraft(Specification(chainWearLinks = 20)).wearLinks)
        assertEquals("", newChainDraft(Specification()).wearLinks)
    }

    @Test fun fieldErrors() {
        assertNull(decimalError("", MeasureKind.CLEARANCE))
        assertEquals(FieldError.FORMAT, decimalError("x", MeasureKind.CLEARANCE))
        assertEquals(FieldError.RANGE, decimalError("10", MeasureKind.CLEARANCE))
        assertEquals(FieldError.DECIMALS, decimalError("1,234", MeasureKind.PRESSURE))
        assertNull(linksError("20"))
        assertEquals(FieldError.LINKS, linksError("1"))
        assertEquals(FieldError.LINKS, linksError("2,5"))
        assertNull(dotError(" 2324 "))
        assertEquals(FieldError.DOT, dotError("23"))
    }

    @Test fun firstErrorCoversEveryTemplate() {
        assertNull(MeasurementDrafts().firstError())
        assertEquals(FieldError.FORMAT, MeasurementDrafts(valves = listOf(ValveDraft(1, ValveKind.INTAKE, 1, shimNew = "a"))).firstError())
        assertEquals(FieldError.RANGE, MeasurementDrafts(brakes = BrakeDraft(discRear = "1000")).firstError())
        assertEquals(FieldError.LINKS, MeasurementDrafts(chain = ChainDraft(wearLinks = "500")).firstError())
        assertEquals(FieldError.DOT, MeasurementDrafts(tires = TireDraft(dotRear = "12")).firstError())
        assertEquals(FieldError.DECIMALS, MeasurementDrafts(tires = TireDraft(pressureFront = "2,505")).firstError())
    }

    @Test fun toMeasurementsParsesAndTrims() {
        val drafts = MeasurementDrafts(
            valves = listOf(ValveDraft(1, ValveKind.INTAKE, 1, measured = "0,12", shimOld = "2,55")),
            chain = ChainDraft(slack = "30", wearLinks = " 20 ", sprocketCondition = "  ", cleaned = true),
            tires = TireDraft(pressureFront = "2,5", dotFront = "2324", conditionRear = " ok "),
        )
        val m = drafts.toMeasurements()
        val valve = m.valves!!.single()
        assertEquals(BigDecimal("0.12"), valve.measured)
        assertEquals(BigDecimal("2.55"), valve.shimOld)
        assertNull(valve.shimNew)
        assertNull(m.brakes)
        assertEquals(BigDecimal("30"), m.chain!!.slack.value)
        assertEquals(20, m.chain!!.wearLinks)
        assertNull(m.chain!!.sprocketCondition)
        assertEquals(true, m.chain!!.cleaned)
        assertEquals(BigDecimal("2.5"), m.tires!!.pressureFront.value)
        assertEquals("2324", m.tires!!.dotFront)
        assertEquals("ok", m.tires!!.conditionRear)
    }

    @Test fun hasAndWithout() {
        val drafts = MeasurementDrafts(brakes = BrakeDraft())
        assertTrue(drafts.has(MeasurementTemplate.BRAKES))
        assertFalse(drafts.has(MeasurementTemplate.TIRES))
        assertEquals(MeasurementDrafts(), drafts.without(MeasurementTemplate.BRAKES))
        MeasurementTemplate.entries.forEach { assertFalse(MeasurementDrafts().without(it).has(it)) }
    }

    @Test fun shimInputsNeedOneCompleteRow() {
        val incomplete = listOf(ValveDraft(1, ValveKind.INTAKE, 1, measured = "0,1"))
        assertNull(incomplete.shimInputs())
        val rows = incomplete + ValveDraft(1, ValveKind.EXHAUST, 1, measured = "0,2", shimOld = "2,5")
        assertEquals(
            listOf(
                ShimInput(ValveKind.INTAKE, BigDecimal("0.1"), null),
                ShimInput(ValveKind.EXHAUST, BigDecimal("0.2"), BigDecimal("2.5")),
            ),
            rows.shimInputs(),
        )
        assertNull("invalid counts as missing", listOf(ValveDraft(1, ValveKind.INTAKE, 1, "x", "2,5")).shimInputs())
    }

    @Test fun rangeStatusMarksValuesOutsideTheTarget() {
        val min = BigDecimal("0.10")
        val max = BigDecimal("0.15")
        assertEquals(MeasurementStatus.OK, rangeStatus("0,12", MeasureKind.CLEARANCE, min, max))
        assertEquals(MeasurementStatus.OUT_OF_RANGE, rangeStatus("0,08", MeasureKind.CLEARANCE, min, max))
        assertEquals(MeasurementStatus.OUT_OF_RANGE, rangeStatus("0,2", MeasureKind.CLEARANCE, null, max))
        assertEquals(MeasurementStatus.OK, rangeStatus("0,15", MeasureKind.CLEARANCE, min, max))
        assertEquals(MeasurementStatus.UNKNOWN, rangeStatus("", MeasureKind.CLEARANCE, min, max))
        assertEquals(MeasurementStatus.UNKNOWN, rangeStatus("0,1", MeasureKind.CLEARANCE, null, null))
    }
}
