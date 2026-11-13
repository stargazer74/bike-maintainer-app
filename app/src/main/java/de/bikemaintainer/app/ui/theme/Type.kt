package de.bikemaintainer.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

// System font on purpose (no downloaded fonts, privacy); Material defaults, sizes are in sp and scale.
private val Defaults = Typography()

/** Material defaults; headings bold (screen titles, top app bars, the mileage on the vehicle screen). */
val Typography = Defaults.copy(
    headlineLarge = Defaults.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = Defaults.headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = Defaults.titleLarge.copy(fontWeight = FontWeight.Bold),
)
