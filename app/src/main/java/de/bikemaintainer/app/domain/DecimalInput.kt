package de.bikemaintainer.app.domain

import java.math.BigDecimal

/** Result of parsing a decimal typed by the user. */
sealed interface DecimalInput {
    data object Empty : DecimalInput

    data class Valid(val value: BigDecimal) : DecimalInput

    data class Invalid(val reason: DecimalError) : DecimalInput
}

enum class DecimalError { FORMAT, RANGE, DECIMALS }

/**
 * Parses a decimal typed by the user, e.g. "0,15" or "0.15": digits with at most one separator, which may be a comma
 * or a point whatever the locale (keyboards differ). No grouping, no sign; surrounding blanks are ignored. The value
 * must lie in 0..[max] and have at most [maxDecimals] decimal places.
 */
fun parseDecimal(text: String, max: BigDecimal, maxDecimals: Int = 3): DecimalInput {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return DecimalInput.Empty
    val normalized = trimmed.replace(',', '.')
    if (!DECIMAL.matches(normalized)) return DecimalInput.Invalid(DecimalError.FORMAT)
    val value = BigDecimal(if (normalized.startsWith('.')) "0$normalized" else normalized)
    return when {
        value > max -> DecimalInput.Invalid(DecimalError.RANGE)
        value.stripTrailingZeros().scale() > maxDecimals -> DecimalInput.Invalid(DecimalError.DECIMALS)
        else -> DecimalInput.Valid(value)
    }
}

/** The value of [parseDecimal], `null` if empty or invalid. */
fun DecimalInput.valueOrNull(): BigDecimal? = (this as? DecimalInput.Valid)?.value

private val DECIMAL = Regex("""\d{1,6}(\.\d*)?|\.\d+""")
