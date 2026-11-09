package de.bikemaintainer.app.domain

const val MAX_MILEAGE = 9_999_999

/**
 * Parses a mileage typed by the user: digits only (surrounding blanks ignored), 0..[MAX_MILEAGE].
 * Grouped numbers like "24.600" are rejected so nothing is misread (e.g. "24,6" as 246).
 */
fun parseMileage(text: String): Int? {
    val digits = text.trim()
    if (digits.isEmpty() || digits.length > MAX_MILEAGE.toString().length || !digits.all(Char::isDigit)) return null
    return digits.toIntOrNull()?.takeIf { it <= MAX_MILEAGE }
}
