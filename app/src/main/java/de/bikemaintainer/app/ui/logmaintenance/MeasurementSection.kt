package de.bikemaintainer.app.ui.logmaintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.LatestMeasurements
import de.bikemaintainer.app.domain.MeasurementContext
import de.bikemaintainer.app.domain.MeasurementLimits
import de.bikemaintainer.app.domain.MeasurementStatus
import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.ShimHint
import de.bikemaintainer.app.domain.ShimSuggestion
import de.bikemaintainer.app.domain.Specification
import de.bikemaintainer.app.domain.ValveAdjustment
import de.bikemaintainer.app.domain.valueOrNull
import de.bikemaintainer.app.ui.common.AppCard
import de.bikemaintainer.app.ui.common.addRes
import de.bikemaintainer.app.ui.common.ErrorText
import de.bikemaintainer.app.ui.common.color
import de.bikemaintainer.app.ui.common.formatNumber
import de.bikemaintainer.app.ui.common.nameRes
import de.bikemaintainer.app.ui.common.removeRes
import de.bikemaintainer.app.ui.common.shown
import de.bikemaintainer.app.ui.common.targetText
import de.bikemaintainer.app.ui.common.valveLabel
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class MeasurementActions(
    val onRetry: () -> Unit,
    val onAdd: (MeasurementTemplate) -> Unit,
    val onRemove: (MeasurementTemplate) -> Unit,
    val onLayout: (cylinders: Int, valvesPerCylinder: Int) -> Unit,
    val onValve: (Int, ValveDraft) -> Unit,
    val onApplyShim: (Int) -> Unit,
    val onBrakes: (BrakeDraft) -> Unit,
    val onChain: (ChainDraft) -> Unit,
    val onTires: (TireDraft) -> Unit,
    /** Opens the camera to read a filled-in worksheet (Epic #120). */
    val onScan: () -> Unit = {},
)

private val CYLINDER_OPTIONS = 1..8
private val VALVES_PER_CYLINDER_OPTIONS = 2..4

/**
 * Optional measurements of the log: one card per added template, add buttons for the templates that fit the vehicle.
 * Without the context (not loaded yet or failed) restored drafts stay editable, only adding is not offered.
 */
@Composable
fun MeasurementSection(state: LogMaintenanceUiState, actions: MeasurementActions) {
    val context = state.measurementContext
    val drafts = state.measurements
    val templates = context?.templates.orEmpty()
    if (context != null && templates.isEmpty() && drafts == MeasurementDrafts()) return
    val enabled = !state.locked
    val spec = context?.specification ?: Specification()
    val latest = context?.latest ?: LatestMeasurements()
    val f = Fields(enabled, state.showMeasurementErrors)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.measure_title), style = MaterialTheme.typography.titleMedium)
        if (context == null && state.measurementLoadError != null) {
            ErrorText(stringResource(R.string.measure_load_failed))
            TextButton(onClick = actions.onRetry, enabled = enabled) { Text(stringResource(R.string.retry)) }
        }
        drafts.valves?.let { ValveCard(it, spec, latest, state.shim, f, actions) }
        drafts.brakes?.let { BrakeCard(it, spec, latest, f, actions) }
        drafts.chain?.let { ChainCard(it, spec, latest, f, actions) }
        drafts.tires?.let { TireCard(it, spec, latest, f, actions) }
        templates.filterNot { drafts.has(it) }.forEach { template ->
            OutlinedButton(onClick = { actions.onAdd(template) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(template.addRes()))
            }
        }
        if (templates.isNotEmpty()) {
            if (state.access.canScan) {
                OutlinedButton(onClick = actions.onScan, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.scan_open))
                }
            } else {
                Text(
                    stringResource(R.string.scan_unavailable_trial),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Shared settings of all fields of the section. */
private data class Fields(val enabled: Boolean, val showErrors: Boolean)

@Composable
private fun TemplateCard(
    template: MeasurementTemplate,
    latest: LatestMeasurements,
    f: Fields,
    onRemove: (MeasurementTemplate) -> Unit,
    content: @Composable () -> Unit,
) {
    val name = stringResource(template.nameRes())
    val locale = LocalConfiguration.current.locales[0]
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRemove(template) }, enabled = f.enabled) {
                    Text(stringResource(template.removeRes()))
                }
            }
            latest.sources[template]?.let { source ->
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
            content()
        }
    }
}

@Composable
private fun FieldErrorText(error: FieldError) = Text(
    stringResource(
        when (error) {
            FieldError.FORMAT -> R.string.measure_error_format
            FieldError.RANGE -> R.string.measure_error_range
            FieldError.DECIMALS -> R.string.measure_error_decimals
            FieldError.LINKS -> R.string.measure_error_links
            FieldError.DOT -> R.string.measure_error_dot
        },
    ),
)

/**
 * A number field. Below it: the error (once the user tried to save), else target and last value, marked red while
 * the typed value lies outside the target.
 */
@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    f: Fields,
    error: FieldError?,
    modifier: Modifier = Modifier,
    target: String? = null,
    previous: BigDecimal? = null,
    status: MeasurementStatus = MeasurementStatus.UNKNOWN,
    decimal: Boolean = true,
) {
    val shownError = error.takeIf { f.showErrors }
    val hints = listOfNotNull(target, previous?.let { stringResource(R.string.measure_previous, it.shown()) })
    val outOfRange = status == MeasurementStatus.OUT_OF_RANGE
    val statusText = if (outOfRange) stringResource(R.string.measure_status_out_of_range) else null
    val hint = (hints + listOfNotNull(statusText)).joinToString(stringResource(R.string.measure_hint_separator))
    val hintColor: Color = if (outOfRange) status.color() else MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = f.enabled,
        isError = shownError != null,
        supportingText = when {
            shownError != null -> ({ FieldErrorText(shownError) })
            hint.isNotEmpty() -> ({ Text(hint, color = hintColor) })
            else -> null
        },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun TextInput(value: String, onChange: (String) -> Unit, label: String, f: Fields) = OutlinedTextField(
    value = value,
    onValueChange = { onChange(it.take(MeasurementLimits.MAX_TEXT)) },
    label = { Text(label) },
    singleLine = true,
    enabled = f.enabled,
    modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun mm(res: Int) = stringResource(R.string.measure_unit_mm, stringResource(res))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayoutChoice(label: String, options: IntRange, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { n ->
            FilterChip(
                selected = n == selected,
                onClick = { onSelect(n) },
                enabled = enabled,
                label = { Text(n.toString()) },
            )
        }
    }
}

@Composable
private fun ValveCard(
    valves: List<ValveDraft>,
    spec: Specification,
    latest: LatestMeasurements,
    shim: ShimState,
    f: Fields,
    actions: MeasurementActions,
) {
    TemplateCard(MeasurementTemplate.VALVE_CLEARANCE, latest, f, actions.onRemove) {
        val layout = valves.layout()
        LayoutChoice(stringResource(R.string.measure_cylinders), CYLINDER_OPTIONS, layout.cylinders, f.enabled) {
            actions.onLayout(it, layout.valvesPerCylinder)
        }
        LayoutChoice(
            stringResource(R.string.measure_valves_per_cylinder),
            VALVES_PER_CYLINDER_OPTIONS,
            layout.valvesPerCylinder,
            f.enabled,
        ) { actions.onLayout(layout.cylinders, it) }
        val withShims = spec.valveAdjustment != ValveAdjustment.SCREW
        if (withShims) ShimNotice(spec, shim)
        val previous = latest.measurements.valves.orEmpty().associateBy { Triple(it.cylinder, it.kind, it.index) }
        valves.forEachIndexed { i, valve ->
            val showIndex = valves.count { it.cylinder == valve.cylinder && it.kind == valve.kind } > 1
            val min = spec.clearanceMin(valve.kind)
            val max = spec.clearanceMax(valve.kind)
            Text(
                valveLabel(valve.cylinder, valve.kind, valve.index, showIndex),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            NumberField(
                value = valve.measured,
                onChange = { actions.onValve(i, valve.copy(measured = it)) },
                label = mm(R.string.measure_valve_measured),
                f = f,
                error = decimalError(valve.measured, MeasureKind.CLEARANCE),
                target = targetText(min, max),
                previous = previous[Triple(valve.cylinder, valve.kind, valve.index)]?.measured,
                status = rangeStatus(valve.measured, MeasureKind.CLEARANCE, min, max),
            )
            if (withShims) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        value = valve.shimOld,
                        onChange = { actions.onValve(i, valve.copy(shimOld = it)) },
                        label = stringResource(R.string.measure_valve_shim_old),
                        f = f,
                        error = decimalError(valve.shimOld, MeasureKind.SHIM),
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        value = valve.shimNew,
                        onChange = { actions.onValve(i, valve.copy(shimNew = it)) },
                        label = stringResource(R.string.measure_valve_shim_new),
                        f = f,
                        error = decimalError(valve.shimNew, MeasureKind.SHIM),
                        modifier = Modifier.weight(1f),
                    )
                }
                shim.suggestions?.getOrNull(i)?.let { ShimLine(it, valve, f.enabled) { actions.onApplyShim(i) } }
            }
        }
    }
}

/** Why there are no shim suggestions: target values missing, or the backend could not be asked (input is kept). */
@Composable
private fun ShimNotice(spec: Specification, shim: ShimState) {
    val text = when {
        !spec.canSuggestShims -> stringResource(R.string.measure_shim_needs_specification)
        shim.error == AppError.Network -> stringResource(R.string.measure_shim_offline)
        shim.error != null -> stringResource(R.string.measure_shim_failed)
        else -> return
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ShimLine(suggestion: ShimSuggestion, valve: ValveDraft, enabled: Boolean, onApply: () -> Unit) {
    val shim = suggestion.suggestedShim
    val lines = buildList {
        when {
            suggestion.hint == ShimHint.NO_CHANGE_NEEDED -> add(stringResource(R.string.measure_shim_no_change))
            shim != null -> {
                val clearance = suggestion.resultingClearance?.shown().orEmpty()
                add(stringResource(R.string.measure_shim_suggestion, shim.shown(), clearance))
            }
        }
        when (suggestion.hint) {
            ShimHint.NO_SHIM_IN_RANGE -> add(stringResource(R.string.measure_shim_no_shim_in_range))
            ShimHint.OUTSIDE_AVAILABLE_SIZES -> add(stringResource(R.string.measure_shim_outside_sizes))
            else -> {}
        }
    }
    if (lines.isEmpty()) return
    val applied = shim != null && parse(valve.shimNew, MeasureKind.SHIM).valueOrNull()?.compareTo(shim) == 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (shim != null && suggestion.hint != ShimHint.NO_CHANGE_NEEDED) {
            TextButton(
                onClick = onApply,
                enabled = enabled && !applied,
            ) { Text(stringResource(R.string.measure_shim_apply)) }
        }
    }
}

/** A length in mm with a lower limit (brake pads and discs). */
@Composable
private fun MinField(value: String, labelRes: Int, min: BigDecimal?, previous: BigDecimal?, f: Fields, onChange: (String) -> Unit) =
    NumberField(
        value = value,
        onChange = onChange,
        label = mm(labelRes),
        f = f,
        error = decimalError(value, MeasureKind.LENGTH),
        target = targetText(min, null),
        previous = previous,
        status = rangeStatus(value, MeasureKind.LENGTH, min, null),
    )

@Composable
private fun BrakeCard(b: BrakeDraft, spec: Specification, latest: LatestMeasurements, f: Fields, actions: MeasurementActions) {
    val last = latest.measurements.brakes
    TemplateCard(MeasurementTemplate.BRAKES, latest, f, actions.onRemove) {
        MinField(b.padFront, R.string.measure_brakes_pad_front, spec.brakePadMinFront, last?.padFront?.value, f) {
            actions.onBrakes(b.copy(padFront = it))
        }
        MinField(b.padRear, R.string.measure_brakes_pad_rear, spec.brakePadMinRear, last?.padRear?.value, f) {
            actions.onBrakes(b.copy(padRear = it))
        }
        MinField(b.discFront, R.string.measure_brakes_disc_front, spec.brakeDiscMinFront, last?.discFront?.value, f) {
            actions.onBrakes(b.copy(discFront = it))
        }
        MinField(b.discRear, R.string.measure_brakes_disc_rear, spec.brakeDiscMinRear, last?.discRear?.value, f) {
            actions.onBrakes(b.copy(discRear = it))
        }
        TextInput(b.discConditionFront, { actions.onBrakes(b.copy(discConditionFront = it)) }, stringResource(R.string.measure_brakes_disc_condition_front), f)
        TextInput(b.discConditionRear, { actions.onBrakes(b.copy(discConditionRear = it)) }, stringResource(R.string.measure_brakes_disc_condition_rear), f)
        TextInput(b.fluidCondition, { actions.onBrakes(b.copy(fluidCondition = it)) }, stringResource(R.string.measure_brakes_fluid), f)
    }
}

@Composable
private fun ChainCard(c: ChainDraft, spec: Specification, latest: LatestMeasurements, f: Fields, actions: MeasurementActions) {
    val last = latest.measurements.chain
    TemplateCard(MeasurementTemplate.CHAIN, latest, f, actions.onRemove) {
        NumberField(
            value = c.slack,
            onChange = { actions.onChain(c.copy(slack = it)) },
            label = mm(R.string.measure_chain_slack),
            f = f,
            error = decimalError(c.slack, MeasureKind.LENGTH),
            target = targetText(spec.chainSlackMin, spec.chainSlackMax),
            previous = last?.slack?.value,
            status = rangeStatus(c.slack, MeasureKind.LENGTH, spec.chainSlackMin, spec.chainSlackMax),
        )
        // The wear limit only applies when measured over the same number of links.
        val sameLinks = spec.chainWearLinks != null && c.wearLinks.trim().toIntOrNull() == spec.chainWearLinks
        val wearMax = spec.chainWearMaxLength.takeIf { sameLinks }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                value = c.wearLength,
                onChange = { actions.onChain(c.copy(wearLength = it)) },
                label = mm(R.string.measure_chain_wear_length),
                f = f,
                error = decimalError(c.wearLength, MeasureKind.LENGTH),
                target = targetText(null, wearMax),
                previous = last?.wearLength?.value,
                status = rangeStatus(c.wearLength, MeasureKind.LENGTH, null, wearMax),
                modifier = Modifier.weight(2f),
            )
            NumberField(
                value = c.wearLinks,
                onChange = { actions.onChain(c.copy(wearLinks = it)) },
                label = stringResource(R.string.measure_chain_wear_links),
                f = f,
                error = linksError(c.wearLinks),
                decimal = false,
                modifier = Modifier.weight(1f),
            )
        }
        TextInput(c.sprocketCondition, { actions.onChain(c.copy(sprocketCondition = it)) }, stringResource(R.string.measure_chain_sprocket), f)
        TextInput(c.rearSprocketCondition, { actions.onChain(c.copy(rearSprocketCondition = it)) }, stringResource(R.string.measure_chain_rear_sprocket), f)
        CheckRow(c.cleaned, f.enabled, { actions.onChain(c.copy(cleaned = it)) }) {
            Text(stringResource(R.string.measure_chain_cleaned), Modifier.weight(1f))
        }
        CheckRow(c.lubricated, f.enabled, { actions.onChain(c.copy(lubricated = it)) }) {
            Text(stringResource(R.string.measure_chain_lubricated), Modifier.weight(1f))
        }
    }
}

@Composable
private fun TireCard(t: TireDraft, spec: Specification, latest: LatestMeasurements, f: Fields, actions: MeasurementActions) {
    val last = latest.measurements.tires
    TemplateCard(MeasurementTemplate.TIRES, latest, f, actions.onRemove) {
        NumberField(
            value = t.pressureFront,
            onChange = { actions.onTires(t.copy(pressureFront = it)) },
            label = stringResource(R.string.measure_unit_bar, stringResource(R.string.measure_tires_pressure_front)),
            f = f,
            error = decimalError(t.pressureFront, MeasureKind.PRESSURE),
            target = targetText(spec.tirePressureFront, spec.tirePressureFront),
            previous = last?.pressureFront?.value,
        )
        NumberField(
            value = t.pressureRear,
            onChange = { actions.onTires(t.copy(pressureRear = it)) },
            label = stringResource(R.string.measure_unit_bar, stringResource(R.string.measure_tires_pressure_rear)),
            f = f,
            error = decimalError(t.pressureRear, MeasureKind.PRESSURE),
            target = targetText(spec.tirePressureRear, spec.tirePressureRear),
            previous = last?.pressureRear?.value,
        )
        NumberField(
            value = t.treadFront,
            onChange = { actions.onTires(t.copy(treadFront = it)) },
            label = mm(R.string.measure_tires_tread_front),
            f = f,
            error = decimalError(t.treadFront, MeasureKind.TREAD),
            target = targetText(spec.treadDepthMin, null),
            previous = last?.treadFront?.value,
            status = rangeStatus(t.treadFront, MeasureKind.TREAD, spec.treadDepthMin, null),
        )
        NumberField(
            value = t.treadRear,
            onChange = { actions.onTires(t.copy(treadRear = it)) },
            label = mm(R.string.measure_tires_tread_rear),
            f = f,
            error = decimalError(t.treadRear, MeasureKind.TREAD),
            target = targetText(spec.treadDepthMin, null),
            previous = last?.treadRear?.value,
            status = rangeStatus(t.treadRear, MeasureKind.TREAD, spec.treadDepthMin, null),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                value = t.dotFront,
                onChange = { actions.onTires(t.copy(dotFront = it.take(4))) },
                label = stringResource(R.string.measure_tires_dot_front),
                f = f,
                error = dotError(t.dotFront),
                decimal = false,
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = t.dotRear,
                onChange = { actions.onTires(t.copy(dotRear = it.take(4))) },
                label = stringResource(R.string.measure_tires_dot_rear),
                f = f,
                error = dotError(t.dotRear),
                decimal = false,
                modifier = Modifier.weight(1f),
            )
        }
        TextInput(t.conditionFront, { actions.onTires(t.copy(conditionFront = it)) }, stringResource(R.string.measure_tires_condition_front), f)
        TextInput(t.conditionRear, { actions.onTires(t.copy(conditionRear = it)) }, stringResource(R.string.measure_tires_condition_rear), f)
    }
}
