package de.bikemaintainer.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import de.bikemaintainer.app.ui.setup.WelcomeScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DarkThemeTest {
    @get:Rule val compose = createComposeRule()

    private fun titleColor(dark: Boolean): Color {
        compose.setContent {
            BikeMaintainerTheme(darkTheme = dark) {
                AppSurface {
                    WelcomeScreen(onOnlineAccount = {}, onOwnServer = {})
                }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Bike Maintainer").fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single().layoutInput.style.color
    }

    @Test fun textOnScreensWithoutScaffoldIsLightInDarkTheme() {
        assertEquals(DarkText, titleColor(dark = true))
    }

    @Test fun textOnScreensWithoutScaffoldIsDarkInLightTheme() {
        assertEquals(LightText, titleColor(dark = false))
    }
}
