package de.bikemaintainer.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.MeasurementStatus
import de.bikemaintainer.app.domain.MeasurementTemplate
import de.bikemaintainer.app.domain.ValveKind
import de.bikemaintainer.app.ui.theme.LocalStatusColors
import java.math.BigDecimal
import java.text.DecimalFormatSymbols

@StringRes
fun MeasurementTemplate.nameRes(): Int = when (this) {
    MeasurementTemplate.VALVE_CLEARANCE -> R.string.measure_template_valve_clearance
    MeasurementTemplate.BRAKES -> R.string.measure_template_brakes
    MeasurementTemplate.CHAIN -> R.string.measure_template_chain
    MeasurementTemplate.TIRES -> R.string.measure_template_tires
}

/** The decimal separator of the app language (',' in German, '.' in English). */
@Composable
fun decimalSeparator(): Char = DecimalFormatSymbols.getInstance(LocalConfiguration.current.locales[0]).decimalSeparator

/** A stored value as shown in the app: "0,15", trailing zeros dropped. */
@Composable
fun BigDecimal.shown(): String = stripTrailingZeros().toPlainString().replace('.', decimalSeparator())

/** "Soll 0,10–0,15", "Soll min. 1,5", "Soll max. 30" or "Soll 2,5"; `null` without any limit. */
@Composable
fun targetText(min: BigDecimal?, max: BigDecimal?): String? = when {
    min != null && max != null && min.compareTo(max) == 0 -> stringResource(R.string.measure_target, min.shown())
    min != null && max != null -> stringResource(R.string.measure_target_range, min.shown(), max.shown())
    min != null -> stringResource(R.string.measure_target, stringResource(R.string.measure_target_min, min.shown()))
    max != null -> stringResource(R.string.measure_target, stringResource(R.string.measure_target_max, max.shown()))
    else -> null
}

/** Red for values outside the target, green within; neutral when nothing can be said. */
@Composable
fun MeasurementStatus.color(): Color = when (this) {
    MeasurementStatus.OUT_OF_RANGE -> LocalStatusColors.current.overdue
    MeasurementStatus.OK -> LocalStatusColors.current.ok
    MeasurementStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** "Zyl. 1 Einlass" or, with two of a kind per cylinder, "Zyl. 1 Einlass 2". */
@Composable
fun valveLabel(cylinder: Int, kind: ValveKind, index: Int, showIndex: Boolean): String {
    val suffix = if (showIndex) " $index" else ""
    val res = if (kind == ValveKind.INTAKE) R.string.measure_valve_intake else R.string.measure_valve_exhaust
    return stringResource(res, cylinder.toString(), suffix)
}
