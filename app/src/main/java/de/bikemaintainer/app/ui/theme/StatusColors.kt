package de.bikemaintainer.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Maintenance status colours (not part of the Material scheme). */
data class StatusColors(val ok: Color, val dueSoon: Color, val overdue: Color)

val DarkStatusColors = StatusColors(
    ok = Color(0xFF34D399),
    dueSoon = Color(0xFFFBBF24),
    overdue = Color(0xFFFB7185),
)

val LightStatusColors = StatusColors(
    ok = Color(0xFF047857),
    dueSoon = Color(0xFFB45309),
    overdue = Color(0xFFBE123C),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
