package de.bikemaintainer.app.ui.login

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de")
class LoginScreenTest {
    @get:Rule(order = 0) val mainRule = MainDispatcherRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val auth = FakeAuthRepository()

    private fun show() {
        val vm = LoginViewModel(auth, "https://h", "Pixel 9", false)
        compose.setContent {
            LoginScreen(viewModel = vm, serverUrl = "https://h", onSignedIn = {}, onOtherServer = {}, onCreateAccount = {})
        }
        compose.onNodeWithText("E-Mail").performTextInput("max@example.org")
        compose.onNodeWithText("Passwort").performTextInput("secret")
    }

    @Test fun showsWebAppErrorTextAfterFailedLogin() {
        auth.deviceLoginResult = Outcome.Failure(AppError.Unauthorized)
        show()
        compose.onNodeWithText("Anmelden").performClick()
        compose.onNodeWithText("E-Mail oder Passwort falsch.").assertExists()
    }

    @Test fun submitButtonIsDisabledWhileLoading() {
        auth.deviceLoginGate = CompletableDeferred()
        show()
        compose.onNodeWithText("Anmelden").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Anmelden").assertIsNotEnabled()
    }

    /** The app offers sign-in only; registration (and a later subscription) stays on the web. */
    @Test fun offersRegistrationButNothingAboutPrices() {
        var created = 0
        val vm = LoginViewModel(auth, "https://h", "Pixel 9", false)
        compose.setContent {
            LoginScreen(viewModel = vm, serverUrl = "https://h", onSignedIn = {}, onOtherServer = {}, onCreateAccount = { created++ })
        }
        compose.onNodeWithText("Konto erstellen").performClick()
        assertEquals(1, created)
        // Play payment policy: no hint at buying anything outside the app.
        for (word in listOf("Abo", "Preis", "kaufen", "€")) {
            assertTrue(word, compose.onAllNodesWithText(word, substring = true, ignoreCase = true).fetchSemanticsNodes().isEmpty())
        }
    }
}
