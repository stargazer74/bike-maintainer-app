package de.bikemaintainer.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Cards, dialogs and menus must use the workshop palette of the web app, not Material's purple/grey defaults. */
class ColorSchemeTest {
    private fun roles(s: ColorScheme) = mapOf(
        "primaryContainer" to s.primaryContainer,
        "onPrimaryContainer" to s.onPrimaryContainer,
        "inversePrimary" to s.inversePrimary,
        "secondary" to s.secondary,
        "onSecondary" to s.onSecondary,
        "secondaryContainer" to s.secondaryContainer,
        "onSecondaryContainer" to s.onSecondaryContainer,
        "tertiary" to s.tertiary,
        "onTertiary" to s.onTertiary,
        "tertiaryContainer" to s.tertiaryContainer,
        "onTertiaryContainer" to s.onTertiaryContainer,
        "error" to s.error,
        "onError" to s.onError,
        "errorContainer" to s.errorContainer,
        "onErrorContainer" to s.onErrorContainer,
        "inverseSurface" to s.inverseSurface,
        "inverseOnSurface" to s.inverseOnSurface,
        "surfaceDim" to s.surfaceDim,
        "surfaceBright" to s.surfaceBright,
        "surfaceContainerLowest" to s.surfaceContainerLowest,
        "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer,
        "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest,
        "outlineVariant" to s.outlineVariant,
    )

    // White/black "on" colours and the lowest container may legitimately equal Material's values.
    private val neutral = setOf(Color.White, Color.Black)

    private fun assertNoMaterialDefaults(scheme: ColorScheme, defaults: ColorScheme) {
        val ours = roles(scheme)
        roles(defaults).filterValues { it !in neutral }.forEach { (role, default) ->
            assertNotEquals(role, default, ours[role])
        }
    }

    @Test fun darkSchemeHasNoMaterialDefaults() = assertNoMaterialDefaults(DarkColorScheme, darkColorScheme())

    @Test fun lightSchemeHasNoMaterialDefaults() = assertNoMaterialDefaults(LightColorScheme, lightColorScheme())

    @Test fun darkSchemeMatchesTheWebApp() {
        val s = DarkColorScheme
        assertEquals(Color(0xFF0B0F19), s.surfaceContainerLowest)
        assertEquals(Color(0xFF0F172A), s.surfaceContainerLow)
        assertEquals(Color(0xFF0F172A), s.surfaceContainer)
        assertEquals(Color(0xFF1E293B), s.surfaceContainerHigh)
        assertEquals(Color(0xFF1E293B), s.surfaceContainerHighest)
        assertEquals(Color(0xFF1E293B), s.outlineVariant)
        assertEquals(Color(0xFFFB7185), s.error)
        assertEquals(Color(0xFF0B0F19), s.onError)
        assertEquals(Color(0xFF0F172A), s.surface)
    }

    @Test fun lightSchemeMatchesTheWebApp() {
        val s = LightColorScheme
        assertEquals(Color(0xFFFFFFFF), s.surfaceContainerLowest)
        assertEquals(Color(0xFFF8FAFC), s.surfaceContainerLow)
        assertEquals(Color(0xFFF1F5F9), s.surfaceContainer)
        assertEquals(Color(0xFFE2E8F0), s.surfaceContainerHigh)
        assertEquals(Color(0xFFE2E8F0), s.surfaceContainerHighest)
        assertEquals(Color(0xFFE2E8F0), s.outlineVariant)
        assertEquals(Color(0xFFBE123C), s.error)
        assertEquals(Color(0xFFFFFFFF), s.onError)
    }
}
