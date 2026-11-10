package de.bikemaintainer.app.data.repository

import de.bikemaintainer.app.api.models.BrakeMeasurementRequest
import de.bikemaintainer.app.api.models.BrakeMeasurementResponse
import de.bikemaintainer.app.api.models.ChainMeasurementRequest
import de.bikemaintainer.app.api.models.ChainMeasurementResponse
import de.bikemaintainer.app.api.models.LatestMeasurementsResponse
import de.bikemaintainer.app.api.models.MaintenanceMeasurementsRequest
import de.bikemaintainer.app.api.models.MaintenanceMeasurementsResponse
import de.bikemaintainer.app.api.models.ShimCalculationInput
import de.bikemaintainer.app.api.models.ShimCalculationResult
import de.bikemaintainer.app.api.models.TireMeasurementRequest
import de.bikemaintainer.app.api.models.TireMeasurementResponse
import de.bikemaintainer.app.api.models.ValveClearanceMeasurementRequest
import de.bikemaintainer.app.api.models.ValveMeasurementRequest
import de.bikemaintainer.app.api.models.ValveMeasurementResponse
import de.bikemaintainer.app.api.models.VehicleSpecifications
import de.bikemaintainer.app.domain.BrakeMeasurement
import de.bikemaintainer.app.domain.ChainMeasurement
import de.bikemaintainer.app.domain.LatestMeasurements
import de.bikemaintainer.app.domain.Measured
import de.bikemaintainer.app.domain.MeasurementSource
import de.bikemaintainer.app.domain.MeasurementStatus
import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.Measurements
import de.bikemaintainer.app.domain.ShimHint
import de.bikemaintainer.app.domain.ShimInput
import de.bikemaintainer.app.domain.ShimSuggestion
import de.bikemaintainer.app.domain.Specification
import de.bikemaintainer.app.domain.TireMeasurement
import de.bikemaintainer.app.domain.ValveAdjustment
import de.bikemaintainer.app.domain.ValveKind
import de.bikemaintainer.app.domain.ValveMeasurement
import de.bikemaintainer.app.api.models.MeasurementSource as ApiMeasurementSource
import de.bikemaintainer.app.api.models.MeasurementStatus as ApiMeasurementStatus
import de.bikemaintainer.app.api.models.ValveKind as ApiValveKind
import de.bikemaintainer.app.api.models.WorksheetTemplate as ApiWorksheetTemplate

// The domain enums use the same constant names as the API.

internal fun ApiWorksheetTemplate.toDomain() = MeasurementTemplate.valueOf(name)

internal fun ApiMeasurementStatus.toDomain() = MeasurementStatus.valueOf(name)

internal fun ApiValveKind.toDomain() = ValveKind.valueOf(name)

internal fun ValveKind.toApi() = ApiValveKind.valueOf(name)

internal fun VehicleSpecifications.toDomain() = Specification(
    cylinders = cylinders,
    valvesPerCylinder = valvesPerCylinder,
    valveAdjustment = valveAdjustment?.let { ValveAdjustment.valueOf(it.name) },
    intakeClearanceMin = intakeClearanceMin,
    intakeClearanceMax = intakeClearanceMax,
    exhaustClearanceMin = exhaustClearanceMin,
    exhaustClearanceMax = exhaustClearanceMax,
    shimStep = shimStep,
    shimMin = shimMin,
    shimMax = shimMax,
    brakePadMinFront = brakePadMinFront,
    brakePadMinRear = brakePadMinRear,
    brakeDiscMinFront = brakeDiscMinFront,
    brakeDiscMinRear = brakeDiscMinRear,
    chainSlackMin = chainSlackMin,
    chainSlackMax = chainSlackMax,
    chainWearLinks = chainWearLinks,
    chainWearMaxLength = chainWearMaxLength,
    tirePressureFront = tirePressureFront,
    tirePressureRear = tirePressureRear,
    treadDepthMin = treadDepthMin,
)

private fun ValveMeasurementResponse.toDomain() =
    ValveMeasurement(cylinder, valve.toDomain(), index, measured, shimOld, shimNew, measuredStatus.toDomain())

private fun BrakeMeasurementResponse.toDomain() = BrakeMeasurement(
    padFront = Measured(padFront, padFrontStatus.toDomain()),
    padRear = Measured(padRear, padRearStatus.toDomain()),
    discFront = Measured(discFront, discFrontStatus.toDomain()),
    discRear = Measured(discRear, discRearStatus.toDomain()),
    discConditionFront = discConditionFront,
    discConditionRear = discConditionRear,
    fluidCondition = fluidCondition,
)

private fun ChainMeasurementResponse.toDomain() = ChainMeasurement(
    slack = Measured(slack, slackStatus.toDomain()),
    wearLength = Measured(wearLength, wearLengthStatus.toDomain()),
    wearLinks = wearLinks,
    sprocketCondition = sprocketCondition,
    rearSprocketCondition = rearSprocketCondition,
    cleaned = cleaned,
    lubricated = lubricated,
)

private fun TireMeasurementResponse.toDomain() = TireMeasurement(
    pressureFront = Measured(pressureFront, pressureFrontStatus.toDomain()),
    pressureRear = Measured(pressureRear, pressureRearStatus.toDomain()),
    treadFront = Measured(treadFront, treadFrontStatus.toDomain()),
    treadRear = Measured(treadRear, treadRearStatus.toDomain()),
    dotFront = dotFront,
    dotRear = dotRear,
    conditionFront = conditionFront,
    conditionRear = conditionRear,
)

internal fun MaintenanceMeasurementsResponse.toDomain() = Measurements(
    valves = valveClearance?.valves?.map { it.toDomain() },
    brakes = brakes?.toDomain(),
    chain = chain?.toDomain(),
    tires = tires?.toDomain(),
)

private fun ApiMeasurementSource.toDomain() = MeasurementSource(logId, performedAt, mileageAtPerformed)

internal fun LatestMeasurementsResponse.toDomain() = LatestMeasurements(
    measurements = measurements.toDomain(),
    sources = buildMap {
        sources.valveClearance?.let { put(MeasurementTemplate.VALVE_CLEARANCE, it.toDomain()) }
        sources.brakes?.let { put(MeasurementTemplate.BRAKES, it.toDomain()) }
        sources.chain?.let { put(MeasurementTemplate.CHAIN, it.toDomain()) }
        sources.tires?.let { put(MeasurementTemplate.TIRES, it.toDomain()) }
    },
)

/** Blank texts are left out (the spec requires at least one character). */
private fun String?.textOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** The request lists every measured template; the statuses are the backend's and are not sent. */
internal fun Measurements.toRequest() = MaintenanceMeasurementsRequest(
    valveClearance = valves?.let { list ->
        ValveClearanceMeasurementRequest(
            list.map { ValveMeasurementRequest(it.cylinder, it.kind.toApi(), it.index, it.measured, it.shimOld, it.shimNew) },
        )
    },
    brakes = brakes?.let {
        BrakeMeasurementRequest(
            padFront = it.padFront.value,
            padRear = it.padRear.value,
            discFront = it.discFront.value,
            discRear = it.discRear.value,
            discConditionFront = it.discConditionFront.textOrNull(),
            discConditionRear = it.discConditionRear.textOrNull(),
            fluidCondition = it.fluidCondition.textOrNull(),
        )
    },
    chain = chain?.let {
        ChainMeasurementRequest(
            slack = it.slack.value,
            wearLength = it.wearLength.value,
            wearLinks = it.wearLinks,
            sprocketCondition = it.sprocketCondition.textOrNull(),
            rearSprocketCondition = it.rearSprocketCondition.textOrNull(),
            cleaned = it.cleaned,
            lubricated = it.lubricated,
        )
    },
    tires = tires?.let {
        TireMeasurementRequest(
            pressureFront = it.pressureFront.value,
            pressureRear = it.pressureRear.value,
            treadFront = it.treadFront.value,
            treadRear = it.treadRear.value,
            dotFront = it.dotFront.textOrNull(),
            dotRear = it.dotRear.textOrNull(),
            conditionFront = it.conditionFront.textOrNull(),
            conditionRear = it.conditionRear.textOrNull(),
        )
    },
)

internal fun ShimInput.toApi() = ShimCalculationInput(kind.toApi(), measured, shimOld)

internal fun ShimCalculationResult.toDomain() =
    ShimSuggestion(suggestedShim, resultingClearance, hint?.let { ShimHint.valueOf(it.name) })
