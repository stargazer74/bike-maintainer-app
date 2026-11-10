package de.bikemaintainer.app.ui.logmaintenance

import de.bikemaintainer.app.domain.BrakeMeasurement
import de.bikemaintainer.app.domain.ChainMeasurement
import de.bikemaintainer.app.domain.DecimalError
import de.bikemaintainer.app.domain.DecimalInput
import de.bikemaintainer.app.domain.Measured
import de.bikemaintainer.app.domain.MeasurementLimits
import de.bikemaintainer.app.domain.MeasurementStatus
import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.Measurements
import de.bikemaintainer.app.domain.ShimInput
import de.bikemaintainer.app.domain.Specification
import de.bikemaintainer.app.domain.TireMeasurement
import de.bikemaintainer.app.domain.ValveKind
import de.bikemaintainer.app.domain.ValveMeasurement
import de.bikemaintainer.app.domain.parseDecimal
import de.bikemaintainer.app.domain.valueOrNull
import java.math.BigDecimal
import kotlinx.serialization.Serializable

/** One valve row while recording; the fields hold the typed text. */
@Serializable
data class ValveDraft(
    val cylinder: Int,
    val kind: ValveKind,
    val index: Int,
    val measured: String = "",
    val shimOld: String = "",
    val shimNew: String = "",
)

@Serializable
data class BrakeDraft(
    val padFront: String = "",
    val padRear: String = "",
    val discFront: String = "",
    val discRear: String = "",
    val discConditionFront: String = "",
    val discConditionRear: String = "",
    val fluidCondition: String = "",
)

@Serializable
data class ChainDraft(
    val slack: String = "",
    val wearLength: String = "",
    val wearLinks: String = "",
    val sprocketCondition: String = "",
    val rearSprocketCondition: String = "",
    val cleaned: Boolean = false,
    val lubricated: Boolean = false,
)

@Serializable
data class TireDraft(
    val pressureFront: String = "",
    val pressureRear: String = "",
    val treadFront: String = "",
    val treadRear: String = "",
    val dotFront: String = "",
    val dotRear: String = "",
    val conditionFront: String = "",
    val conditionRear: String = "",
)

/** Measurements of the log being recorded; `null` means the template is not part of it. Kept across process death. */
@Serializable
data class MeasurementDrafts(
    val valves: List<ValveDraft>? = null,
    val brakes: BrakeDraft? = null,
    val chain: ChainDraft? = null,
    val tires: TireDraft? = null,
) {
    fun has(template: MeasurementTemplate): Boolean = when (template) {
        MeasurementTemplate.VALVE_CLEARANCE -> valves != null
        MeasurementTemplate.BRAKES -> brakes != null
        MeasurementTemplate.CHAIN -> chain != null
        MeasurementTemplate.TIRES -> tires != null
    }

    fun without(template: MeasurementTemplate): MeasurementDrafts = when (template) {
        MeasurementTemplate.VALVE_CLEARANCE -> copy(valves = null)
        MeasurementTemplate.BRAKES -> copy(brakes = null)
        MeasurementTemplate.CHAIN -> copy(chain = null)
        MeasurementTemplate.TIRES -> copy(tires = null)
    }
}

/** Kinds of input, each with the limits of the API. */
enum class MeasureKind(val max: BigDecimal, val decimals: Int) {
    CLEARANCE(MeasurementLimits.CLEARANCE, 3),
    SHIM(MeasurementLimits.SHIM, 3),
    LENGTH(MeasurementLimits.LENGTH, 3),
    TREAD(MeasurementLimits.TREAD, 3),
    PRESSURE(MeasurementLimits.PRESSURE, 2),
}

/** What is wrong with one field; shown below it once the user tried to save. */
enum class FieldError { FORMAT, RANGE, DECIMALS, LINKS, DOT }

fun parse(text: String, kind: MeasureKind): DecimalInput = parseDecimal(text, kind.max, kind.decimals)

fun decimalError(text: String, kind: MeasureKind): FieldError? = when (val input = parse(text, kind)) {
    is DecimalInput.Invalid -> when (input.reason) {
        DecimalError.FORMAT -> FieldError.FORMAT
        DecimalError.RANGE -> FieldError.RANGE
        DecimalError.DECIMALS -> FieldError.DECIMALS
    }
    else -> null
}

fun linksError(text: String): FieldError? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val links = trimmed.toIntOrNull()
    return if (links != null && links in MeasurementLimits.MIN_LINKS..MeasurementLimits.MAX_LINKS) null else FieldError.LINKS
}

fun dotError(text: String): FieldError? {
    val trimmed = text.trim()
    return if (trimmed.isEmpty() || DOT.matches(trimmed)) null else FieldError.DOT
}

private val DOT = Regex("""\d{4}""")

/** Valves of one cylinder: 2 = intake, exhaust; 3 = two intakes, one exhaust; 4 = two of each. */
private fun cylinderValves(valvesPerCylinder: Int): List<Pair<ValveKind, Int>> {
    val intakes = if (valvesPerCylinder >= 3) 2 else 1
    val exhausts = if (valvesPerCylinder >= 4) 2 else 1
    return (1..intakes).map { ValveKind.INTAKE to it } + (1..exhausts).map { ValveKind.EXHAUST to it }
}

private fun ValveDraft.key() = Triple(cylinder, kind, index)

/**
 * Valve rows for an engine layout, ordered by cylinder, intake before exhaust, then index (like the worksheet).
 * Rows of [previous] at the same position keep their values.
 */
fun valveLayout(cylinders: Int, valvesPerCylinder: Int, previous: List<ValveDraft> = emptyList()): List<ValveDraft> {
    val kept = previous.associateBy { it.key() }
    return (1..cylinders).flatMap { cylinder ->
        cylinderValves(valvesPerCylinder).map { (kind, index) ->
            kept[Triple(cylinder, kind, index)] ?: ValveDraft(cylinder, kind, index)
        }
    }
}

data class EngineLayout(val cylinders: Int, val valvesPerCylinder: Int)

fun layoutOf(valves: List<Pair<Int, Any>>): EngineLayout =
    EngineLayout(
        cylinders = valves.maxOf { it.first },
        valvesPerCylinder = valves.count { it.first == 1 }.coerceIn(2, 4),
    )

fun List<ValveDraft>.layout(): EngineLayout = layoutOf(map { it.cylinder to it.kind })

const val DEFAULT_CYLINDERS = 2
const val DEFAULT_VALVES_PER_CYLINDER = 4

/**
 * Rows for a new valve clearance measurement: layout from the vehicle, else from the last measurement, else the
 * default. "Shim old" is the shim installed last time (its new shim, or the old one if it was not changed), written
 * with [decimalSeparator] so it reads like the user's own input.
 */
fun newValveDrafts(specification: Specification, latest: List<ValveMeasurement>?, decimalSeparator: Char): List<ValveDraft> {
    val fromLatest = latest?.takeIf { it.isNotEmpty() }?.let { list -> layoutOf(list.map { it.cylinder to it.kind }) }
    val cylinders = specification.cylinders ?: fromLatest?.cylinders ?: DEFAULT_CYLINDERS
    val perCylinder = specification.valvesPerCylinder ?: fromLatest?.valvesPerCylinder ?: DEFAULT_VALVES_PER_CYLINDER
    val installed = latest.orEmpty().associate { Triple(it.cylinder, it.kind, it.index) to (it.shimNew ?: it.shimOld) }
    return valveLayout(cylinders, perCylinder).map { draft ->
        draft.copy(shimOld = installed[draft.key()]?.toInput(decimalSeparator).orEmpty())
    }
}

/** A stored value as the user would type it, e.g. "2,550" → "2,55". */
fun BigDecimal.toInput(decimalSeparator: Char): String =
    stripTrailingZeros().toPlainString().replace('.', decimalSeparator)

/** The number of links is taken from the vehicle's wear limit, so the measurement can be checked against it. */
fun newChainDraft(specification: Specification) = ChainDraft(wearLinks = specification.chainWearLinks?.toString().orEmpty())

/** The first problem of all fields, or `null` if the measurements can be saved. */
fun MeasurementDrafts.firstError(): FieldError? {
    val errors = buildList {
        valves.orEmpty().forEach {
            add(decimalError(it.measured, MeasureKind.CLEARANCE))
            add(decimalError(it.shimOld, MeasureKind.SHIM))
            add(decimalError(it.shimNew, MeasureKind.SHIM))
        }
        brakes?.let { b ->
            listOf(b.padFront, b.padRear, b.discFront, b.discRear).forEach { add(decimalError(it, MeasureKind.LENGTH)) }
        }
        chain?.let {
            add(decimalError(it.slack, MeasureKind.LENGTH))
            add(decimalError(it.wearLength, MeasureKind.LENGTH))
            add(linksError(it.wearLinks))
        }
        tires?.let {
            add(decimalError(it.pressureFront, MeasureKind.PRESSURE))
            add(decimalError(it.pressureRear, MeasureKind.PRESSURE))
            add(decimalError(it.treadFront, MeasureKind.TREAD))
            add(decimalError(it.treadRear, MeasureKind.TREAD))
            add(dotError(it.dotFront))
            add(dotError(it.dotRear))
        }
    }
    return errors.firstOrNull { it != null }
}

private fun String.value(kind: MeasureKind) = Measured(parse(this, kind).valueOrNull())

private fun String.textOrNull() = trim().takeIf { it.isNotEmpty() }?.take(MeasurementLimits.MAX_TEXT)

/** The measurements to send; call only when [firstError] is `null`. Empty fields are left out. */
fun MeasurementDrafts.toMeasurements() = Measurements(
    valves = valves?.map {
        ValveMeasurement(
            cylinder = it.cylinder,
            kind = it.kind,
            index = it.index,
            measured = parse(it.measured, MeasureKind.CLEARANCE).valueOrNull(),
            shimOld = parse(it.shimOld, MeasureKind.SHIM).valueOrNull(),
            shimNew = parse(it.shimNew, MeasureKind.SHIM).valueOrNull(),
        )
    },
    brakes = brakes?.let {
        BrakeMeasurement(
            padFront = it.padFront.value(MeasureKind.LENGTH),
            padRear = it.padRear.value(MeasureKind.LENGTH),
            discFront = it.discFront.value(MeasureKind.LENGTH),
            discRear = it.discRear.value(MeasureKind.LENGTH),
            discConditionFront = it.discConditionFront.textOrNull(),
            discConditionRear = it.discConditionRear.textOrNull(),
            fluidCondition = it.fluidCondition.textOrNull(),
        )
    },
    chain = chain?.let {
        ChainMeasurement(
            slack = it.slack.value(MeasureKind.LENGTH),
            wearLength = it.wearLength.value(MeasureKind.LENGTH),
            wearLinks = it.wearLinks.trim().toIntOrNull(),
            sprocketCondition = it.sprocketCondition.textOrNull(),
            rearSprocketCondition = it.rearSprocketCondition.textOrNull(),
            cleaned = it.cleaned,
            lubricated = it.lubricated,
        )
    },
    tires = tires?.let {
        TireMeasurement(
            pressureFront = it.pressureFront.value(MeasureKind.PRESSURE),
            pressureRear = it.pressureRear.value(MeasureKind.PRESSURE),
            treadFront = it.treadFront.value(MeasureKind.TREAD),
            treadRear = it.treadRear.value(MeasureKind.TREAD),
            dotFront = it.dotFront.textOrNull(),
            dotRear = it.dotRear.textOrNull(),
            conditionFront = it.conditionFront.textOrNull(),
            conditionRear = it.conditionRear.textOrNull(),
        )
    },
)

/**
 * Inputs for the shim calculation, or `null` if no row has both a valid measured value and old shim (nothing to
 * calculate). Invalid fields count as missing.
 */
fun List<ValveDraft>.shimInputs(): List<ShimInput>? {
    val inputs = map {
        ShimInput(
            it.kind,
            parse(it.measured, MeasureKind.CLEARANCE).valueOrNull(),
            parse(it.shimOld, MeasureKind.SHIM).valueOrNull(),
        )
    }
    return inputs.takeIf { list -> list.any { it.measured != null && it.shimOld != null } }
}

/**
 * Typed value against the target values while recording, like the web's Soll/Ist marking: [MeasurementStatus.UNKNOWN]
 * without a valid value or without a limit, else OK or OUT_OF_RANGE. The backend checks again when saving.
 */
fun rangeStatus(text: String, kind: MeasureKind, min: BigDecimal?, max: BigDecimal?): MeasurementStatus {
    val value = parse(text, kind).valueOrNull() ?: return MeasurementStatus.UNKNOWN
    if (min == null && max == null) return MeasurementStatus.UNKNOWN
    val below = min != null && value < min
    val above = max != null && value > max
    return if (below || above) MeasurementStatus.OUT_OF_RANGE else MeasurementStatus.OK
}
