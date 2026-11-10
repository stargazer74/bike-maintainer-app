package de.bikemaintainer.app.ui.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.LatestMeasurements
import de.bikemaintainer.app.domain.Measured
import de.bikemaintainer.app.domain.MeasurementContext
import de.bikemaintainer.app.domain.MeasurementStatus
import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.Specification
import de.bikemaintainer.app.domain.ValveAdjustment
import de.bikemaintainer.app.domain.ValveMeasurement
import de.bikemaintainer.app.ui.common.AppCard
import de.bikemaintainer.app.ui.common.StatusDot
import de.bikemaintainer.app.ui.common.color
import de.bikemaintainer.app.ui.common.formatNumber
import de.bikemaintainer.app.ui.common.nameRes
import de.bikemaintainer.app.ui.common.shown
import de.bikemaintainer.app.ui.common.valveLabel
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One line of a card: label, value and the backend's check (dot and text only when outside the target). */
private data class Line(val label: String, val value: String, val status: MeasurementStatus = MeasurementStatus.UNKNOWN)

@Composable
private fun LineRow(line: Line) {
    val outOfRange = line.status == MeasurementStatus.OUT_OF_RANGE
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(line.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (outOfRange) StatusDot(line.status.color())
        Text(line.value, style = MaterialTheme.typography.bodyMedium)
        if (outOfRange) {
            Text(
                stringResource(R.string.measure_status_out_of_range),
                style = MaterialTheme.typography.bodySmall,
                color = line.status.color(),
            )
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun mmText(value: BigDecimal) = stringResource(R.string.measure_value_mm, value.shown())

@Composable
private fun barText(value: BigDecimal) = stringResource(R.string.measure_value_bar, value.shown())

@Composable
private fun measuredLine(labelRes: Int, measured: Measured?, bar: Boolean = false): Line? {
    val value = measured?.value ?: return null
    return Line(stringResource(labelRes), if (bar) barText(value) else mmText(value), measured.status)
}

@Composable
private fun textLine(labelRes: Int, text: String?): Line? = text?.let { Line(stringResource(labelRes), it) }

@Composable
private fun valveLines(valves: List<ValveMeasurement>): List<Line> = valves.map { v ->
    val showIndex = valves.count { it.cylinder == v.cylinder && it.kind == v.kind } > 1
    val parts = listOfNotNull(
        v.measured?.let { mmText(it) },
        when {
            v.shimOld != null && v.shimNew != null && v.shimOld.compareTo(v.shimNew) != 0 ->
                stringResource(R.string.measure_shim_change, v.shimOld.shown(), v.shimNew.shown())
            v.shimOld != null -> stringResource(R.string.measure_shim_unchanged, v.shimOld.shown())
            v.shimNew != null -> stringResource(R.string.measure_shim_unchanged, v.shimNew.shown())
            else -> null
        },
    )
    Line(valveLabel(v.cylinder, v.kind, v.index, showIndex), parts.joinToString(", "), v.status)
}

@Composable
private fun latestLines(template: MeasurementTemplate, latest: LatestMeasurements): List<Line> {
    val m = latest.measurements
    val yes = stringResource(R.string.measure_yes)
    return when (template) {
        MeasurementTemplate.VALVE_CLEARANCE -> valveLines(m.valves.orEmpty())
        MeasurementTemplate.BRAKES -> m.brakes?.let { b ->
            listOfNotNull(
                measuredLine(R.string.measure_brakes_pad_front, b.padFront),
                measuredLine(R.string.measure_brakes_pad_rear, b.padRear),
                measuredLine(R.string.measure_brakes_disc_front, b.discFront),
                measuredLine(R.string.measure_brakes_disc_rear, b.discRear),
                textLine(R.string.measure_brakes_disc_condition_front, b.discConditionFront),
                textLine(R.string.measure_brakes_disc_condition_rear, b.discConditionRear),
                textLine(R.string.measure_brakes_fluid, b.fluidCondition),
            )
        }.orEmpty()
        MeasurementTemplate.CHAIN -> m.chain?.let { c ->
            listOfNotNull(
                measuredLine(R.string.measure_chain_slack, c.slack),
                measuredLine(R.string.measure_chain_wear_length, c.wearLength),
                c.wearLinks?.let { Line(stringResource(R.string.measure_chain_wear_links), it.toString()) },
                textLine(R.string.measure_chain_sprocket, c.sprocketCondition),
                textLine(R.string.measure_chain_rear_sprocket, c.rearSprocketCondition),
                textLine(R.string.measure_chain_cleaned, yes.takeIf { c.cleaned == true }),
                textLine(R.string.measure_chain_lubricated, yes.takeIf { c.lubricated == true }),
            )
        }.orEmpty()
        MeasurementTemplate.TIRES -> m.tires?.let { t ->
            listOfNotNull(
                measuredLine(R.string.measure_tires_pressure_front, t.pressureFront, bar = true),
                measuredLine(R.string.measure_tires_pressure_rear, t.pressureRear, bar = true),
                measuredLine(R.string.measure_tires_tread_front, t.treadFront),
                measuredLine(R.string.measure_tires_tread_rear, t.treadRear),
                textLine(R.string.measure_tires_dot_front, t.dotFront),
                textLine(R.string.measure_tires_dot_rear, t.dotRear),
                textLine(R.string.measure_tires_condition_front, t.conditionFront),
                textLine(R.string.measure_tires_condition_rear, t.conditionRear),
            )
        }.orEmpty()
    }
}

/** "Letzte Messwerte": per template the values of the newest log that measured it. */
@Composable
fun LatestMeasurementsCard(context: MeasurementContext) {
    val locale = LocalConfiguration.current.locales[0]
    Card(stringResource(R.string.vehicle_measurements_title)) {
        val shown = context.templates.mapNotNull { template ->
            latestLines(template, context.latest).takeIf { it.isNotEmpty() }?.let { template to it }
        }
        if (shown.isEmpty()) {
            Text(
                stringResource(R.string.vehicle_measurements_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        shown.forEach { (template, lines) ->
            Text(
                stringResource(template.nameRes()),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            context.latest.sources[template]?.let { source ->
                Text(
                    stringResource(
                        R.string.measure_last,
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(source.performedAt),
                        formatNumber(source.mileage),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            lines.forEach { LineRow(it) }
        }
    }
}

@Composable
private fun rangeText(min: BigDecimal?, max: BigDecimal?): String? = when {
    min != null && max != null -> stringResource(R.string.measure_value_mm, stringResource(R.string.measure_range, min.shown(), max.shown()))
    min != null -> stringResource(R.string.measure_target_min, mmText(min))
    max != null -> stringResource(R.string.measure_target_max, mmText(max))
    else -> null
}

@Composable
private fun specLines(spec: Specification): List<Line> {
    @Composable
    fun line(labelRes: Int, value: String?) = value?.let { Line(stringResource(labelRes), it) }
    return listOfNotNull(
        line(R.string.spec_cylinders, spec.cylinders?.toString()),
        line(R.string.spec_valves_per_cylinder, spec.valvesPerCylinder?.toString()),
        line(
            R.string.spec_adjustment,
            spec.valveAdjustment?.let {
                stringResource(if (it == ValveAdjustment.SHIM) R.string.spec_adjustment_shim else R.string.spec_adjustment_screw)
            },
        ),
        line(R.string.spec_intake, rangeText(spec.intakeClearanceMin, spec.intakeClearanceMax)),
        line(R.string.spec_exhaust, rangeText(spec.exhaustClearanceMin, spec.exhaustClearanceMax)),
        line(R.string.spec_shim_step, spec.shimStep?.let { mmText(it) }),
        line(R.string.spec_shim_sizes, rangeText(spec.shimMin, spec.shimMax)),
        line(R.string.spec_pad_min_front, spec.brakePadMinFront?.let { mmText(it) }),
        line(R.string.spec_pad_min_rear, spec.brakePadMinRear?.let { mmText(it) }),
        line(R.string.spec_disc_min_front, spec.brakeDiscMinFront?.let { mmText(it) }),
        line(R.string.spec_disc_min_rear, spec.brakeDiscMinRear?.let { mmText(it) }),
        line(R.string.spec_chain_slack, rangeText(spec.chainSlackMin, spec.chainSlackMax)),
        line(
            R.string.spec_chain_wear,
            if (spec.chainWearMaxLength != null && spec.chainWearLinks != null) {
                stringResource(R.string.spec_chain_wear_value, spec.chainWearMaxLength.shown(), spec.chainWearLinks)
            } else {
                null
            },
        ),
        line(R.string.spec_pressure_front, spec.tirePressureFront?.let { barText(it) }),
        line(R.string.spec_pressure_rear, spec.tirePressureRear?.let { barText(it) }),
        line(R.string.spec_tread_min, spec.treadDepthMin?.let { mmText(it) }),
    )
}

/** "Sollwerte", read-only: they are maintained in the web app, which the hint says. */
@Composable
fun SpecificationCard(spec: Specification) {
    Card(stringResource(R.string.vehicle_specification_title)) {
        val lines = specLines(spec)
        val hint = if (lines.isEmpty()) R.string.vehicle_specification_empty else R.string.vehicle_specification_hint
        lines.forEach { LineRow(it) }
        Text(
            stringResource(hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
