package de.bikemaintainer.app.domain

import java.math.BigDecimal
import java.time.LocalDate

/** Worksheet templates; the backend says which ones fit a vehicle type. */
enum class MeasurementTemplate { VALVE_CLEARANCE, BRAKES, CHAIN, TIRES }

enum class ValveKind { INTAKE, EXHAUST }

enum class ValveAdjustment { SHIM, SCREW }

/** A measured value checked by the backend against the vehicle's target values. */
enum class MeasurementStatus { OK, OUT_OF_RANGE, UNKNOWN }

enum class ShimHint { NO_CHANGE_NEEDED, NO_SHIM_IN_RANGE, OUTSIDE_AVAILABLE_SIZES, MISSING_INPUT, NOT_SHIM_ADJUSTED }

/** Target values of a vehicle from the workshop manual; maintained in the web app only. Lengths in mm, pressure in bar. */
data class Specification(
    val cylinders: Int? = null,
    val valvesPerCylinder: Int? = null,
    val valveAdjustment: ValveAdjustment? = null,
    val intakeClearanceMin: BigDecimal? = null,
    val intakeClearanceMax: BigDecimal? = null,
    val exhaustClearanceMin: BigDecimal? = null,
    val exhaustClearanceMax: BigDecimal? = null,
    val shimStep: BigDecimal? = null,
    val shimMin: BigDecimal? = null,
    val shimMax: BigDecimal? = null,
    val brakePadMinFront: BigDecimal? = null,
    val brakePadMinRear: BigDecimal? = null,
    val brakeDiscMinFront: BigDecimal? = null,
    val brakeDiscMinRear: BigDecimal? = null,
    val chainSlackMin: BigDecimal? = null,
    val chainSlackMax: BigDecimal? = null,
    val chainWearLinks: Int? = null,
    val chainWearMaxLength: BigDecimal? = null,
    val tirePressureFront: BigDecimal? = null,
    val tirePressureRear: BigDecimal? = null,
    val treadDepthMin: BigDecimal? = null,
) {
    val isEmpty: Boolean get() = this == Specification()

    val shimAdjusted: Boolean get() = valveAdjustment != ValveAdjustment.SCREW

    /** The backend can only suggest shims with both target ranges and the step. */
    val canSuggestShims: Boolean
        get() = shimAdjusted && shimStep != null && intakeClearanceMin != null && intakeClearanceMax != null &&
            exhaustClearanceMin != null && exhaustClearanceMax != null

    fun clearanceMin(kind: ValveKind) = if (kind == ValveKind.INTAKE) intakeClearanceMin else exhaustClearanceMin

    fun clearanceMax(kind: ValveKind) = if (kind == ValveKind.INTAKE) intakeClearanceMax else exhaustClearanceMax
}

/** A measured value with its check; [value] `null` means not measured. */
data class Measured(val value: BigDecimal?, val status: MeasurementStatus = MeasurementStatus.UNKNOWN)

data class ValveMeasurement(
    val cylinder: Int,
    val kind: ValveKind,
    val index: Int,
    val measured: BigDecimal?,
    val shimOld: BigDecimal?,
    val shimNew: BigDecimal?,
    val status: MeasurementStatus = MeasurementStatus.UNKNOWN,
)

data class BrakeMeasurement(
    val padFront: Measured = Measured(null),
    val padRear: Measured = Measured(null),
    val discFront: Measured = Measured(null),
    val discRear: Measured = Measured(null),
    val discConditionFront: String? = null,
    val discConditionRear: String? = null,
    val fluidCondition: String? = null,
)

data class ChainMeasurement(
    val slack: Measured = Measured(null),
    val wearLength: Measured = Measured(null),
    val wearLinks: Int? = null,
    val sprocketCondition: String? = null,
    val rearSprocketCondition: String? = null,
    val cleaned: Boolean? = null,
    val lubricated: Boolean? = null,
)

data class TireMeasurement(
    val pressureFront: Measured = Measured(null),
    val pressureRear: Measured = Measured(null),
    val treadFront: Measured = Measured(null),
    val treadRear: Measured = Measured(null),
    val dotFront: String? = null,
    val dotRear: String? = null,
    val conditionFront: String? = null,
    val conditionRear: String? = null,
)

/** Measured values of one maintenance log; `null` means the template was not measured. */
data class Measurements(
    val valves: List<ValveMeasurement>? = null,
    val brakes: BrakeMeasurement? = null,
    val chain: ChainMeasurement? = null,
    val tires: TireMeasurement? = null,
) {
    val isEmpty: Boolean get() = valves == null && brakes == null && chain == null && tires == null
}

/** The maintenance log a set of latest measurements comes from. */
data class MeasurementSource(val logId: Long, val performedAt: LocalDate, val mileage: Int)

data class LatestMeasurements(
    val measurements: Measurements = Measurements(),
    val sources: Map<MeasurementTemplate, MeasurementSource> = emptyMap(),
)

/** What recording and showing measurements needs for one vehicle. */
data class MeasurementContext(
    val templates: List<MeasurementTemplate>,
    val specification: Specification,
    val latest: LatestMeasurements,
)

data class ShimInput(val kind: ValveKind, val measured: BigDecimal?, val shimOld: BigDecimal?)

data class ShimSuggestion(val suggestedShim: BigDecimal?, val resultingClearance: BigDecimal?, val hint: ShimHint?)

/** Highest values the backend accepts. */
object MeasurementLimits {
    val CLEARANCE = BigDecimal("9.999")
    val SHIM = BigDecimal("99.999")
    val LENGTH = BigDecimal("999.999")
    val TREAD = BigDecimal("99.999")
    val PRESSURE = BigDecimal("10")
    const val MIN_LINKS = 2
    const val MAX_LINKS = 200
    const val MAX_TEXT = 100
}
