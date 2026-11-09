package de.bikemaintainer.app.ui.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemeSettingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun reset() = AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    @Test fun defaultIsSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeSetting.current(context))
    }

    @Test fun setStoresAndAppliesTheMode() {
        ThemeSetting.set(context, ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, ThemeSetting.current(context))
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())

        ThemeSetting.set(context, ThemeMode.LIGHT)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())
    }

    @Test fun applyRestoresTheStoredModeAtStart() {
        ThemeSetting.set(context, ThemeMode.DARK)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        ThemeSetting.apply(context)

        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())
    }

    @Test fun unknownStoredValueFallsBackToSystem() {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("theme_mode", "sepia").commit()
        assertEquals(ThemeMode.SYSTEM, ThemeSetting.current(context))
    }

    @Test fun selectedFollowsTheAppliedMode() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        assertEquals(ThemeMode.DARK, ThemeMode.applied())
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        assertEquals(ThemeMode.SYSTEM, ThemeMode.applied())
    }
}
