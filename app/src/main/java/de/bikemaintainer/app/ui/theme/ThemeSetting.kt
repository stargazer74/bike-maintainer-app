package de.bikemaintainer.app.ui.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

/** Light/dark choice in the settings, applied via AppCompat's night mode (isSystemInDarkTheme follows it). */
enum class ThemeMode(internal val stored: String, internal val nightMode: Int) {
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES),
    ;

    companion object {
        /** The mode currently applied; anything else than light or dark counts as system. */
        fun applied(): ThemeMode = entries.firstOrNull {
            it != SYSTEM && it.nightMode == AppCompatDelegate.getDefaultNightMode()
        } ?: SYSTEM
    }
}

/**
 * Stores the design mode like the language: per device, independent of server and login. Therefore not in
 * SettingsStore, which is cleared on a server switch. AppCompat does not persist the night mode itself, so [apply]
 * runs in Application.onCreate, before the first activity draws (no flash of the wrong theme).
 */
object ThemeSetting {
    private const val PREFS = "ui"
    private const val KEY = "theme_mode"

    fun current(context: Context): ThemeMode {
        val stored = prefs(context).getString(KEY, null)
        return ThemeMode.entries.firstOrNull { it.stored == stored } ?: ThemeMode.SYSTEM
    }

    /** Saves and applies the mode; AppCompat recreates the open activities. */
    fun set(context: Context, mode: ThemeMode) {
        prefs(context).edit { putString(KEY, mode.stored) }
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    fun apply(context: Context) = AppCompatDelegate.setDefaultNightMode(current(context).nightMode)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
