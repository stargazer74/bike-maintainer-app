package de.bikemaintainer.app.ui.login

import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.MainDispatcherRule
import de.bikemaintainer.app.ui.common.WebLinks
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LoginViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val auth = FakeAuthRepository()

    private fun viewModel(relogin: Boolean = false) =
        LoginViewModel(auth, "https://h", "Pixel 9", relogin)

    private fun LoginViewModel.fill(email: String = " max@example.org ", password: String = "secret") {
        onEmailChange(email)
        onPasswordChange(password)
    }

    @Test fun successfulLoginIsDone() {
        val vm = viewModel()
        vm.fill()
        vm.onSubmit()
        assertEquals(listOf(listOf("https://h", "max@example.org", "secret", "Pixel 9")), auth.deviceLoginCalls)
        assertTrue(vm.state.value.done)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
    }

    @Test fun emptyFieldsAreRequiredWithoutRequest() {
        val vm = viewModel()
        vm.onSubmit()
        assertEquals(LoginError.REQUIRED, vm.state.value.error)
        vm.fill(email = "  ", password = "x")
        vm.onSubmit()
        assertEquals(LoginError.REQUIRED, vm.state.value.error)
        vm.fill()
        vm.onDeviceNameChange("   ")
        vm.onSubmit()
        assertEquals(LoginError.REQUIRED, vm.state.value.error)
        assertEquals(0, auth.deviceLoginCalls.size)
    }

    @Test fun errorsAreMappedLikeTheWebApp() {
        val cases = listOf(
            AppError.Unauthorized to LoginError.INVALID_CREDENTIALS,
            AppError.Http(401, null) to LoginError.INVALID_CREDENTIALS,
            AppError.Http(403, "ACCOUNT_DISABLED") to LoginError.ACCOUNT_DISABLED,
            AppError.Http(403, "EMAIL_NOT_VERIFIED") to LoginError.EMAIL_NOT_VERIFIED,
            AppError.Http(429, null) to LoginError.TOO_MANY_ATTEMPTS,
            AppError.Http(500, null) to LoginError.UNKNOWN,
            AppError.Network to LoginError.NOT_REACHABLE,
            AppError.CleartextNotAllowed to LoginError.CLEARTEXT_NOT_ALLOWED,
        )
        for ((error, expected) in cases) {
            auth.deviceLoginResult = Outcome.Failure(error)
            val vm = viewModel()
            vm.fill()
            vm.onSubmit()
            assertEquals("$error", expected, vm.state.value.error)
            assertFalse(vm.state.value.done)
        }
    }

    @Test fun submitIsIgnoredWhileLoading() {
        val gate = CompletableDeferred<Unit>()
        auth.deviceLoginGate = gate
        val vm = viewModel()
        vm.fill()
        vm.onSubmit()
        assertTrue(vm.state.value.loading)
        vm.onSubmit()
        gate.complete(Unit)
        assertEquals(1, auth.deviceLoginCalls.size)
    }

    @Test fun passwordIsClearedAfterFailure() {
        auth.deviceLoginResult = Outcome.Failure(AppError.Unauthorized)
        val vm = viewModel()
        vm.fill()
        vm.onSubmit()
        assertEquals("", vm.state.value.password)
        assertEquals(" max@example.org ", vm.state.value.email)
    }

    @Test fun passwordIsNotSavedInState() {
        val vm = viewModel()
        vm.fill()
        vm.onSubmit()
        assertEquals("", vm.state.value.password)
        // The view model has no SavedStateHandle: its only constructor takes no handle.
        assertTrue(LoginViewModel::class.java.constructors.none { c ->
            c.parameterTypes.any { it.name.contains("SavedStateHandle") }
        })
    }

    @Test fun reloginShowsHint() {
        assertTrue(viewModel(relogin = true).state.value.reloginHint)
        assertFalse(viewModel().state.value.reloginHint)
    }

    @Test fun deviceNameIsPrefilledAndEditable() {
        val vm = viewModel()
        assertEquals("Pixel 9", vm.state.value.deviceName)
        vm.onDeviceNameChange("Work phone")
        vm.fill()
        vm.onSubmit()
        assertEquals("Work phone", auth.deviceLoginCalls.single()[3])
    }

    @Test fun webLinksUseServerAndLanguage() {
        assertEquals("https://h/registrieren", WebLinks.register("https://h/"))
        assertEquals("https://h/passwort-vergessen", WebLinks.forgotPassword("https://h"))
        assertEquals("https://w/en/legal-notice/", WebLinks.imprint("https://w", false))
        assertEquals("https://w/impressum/", WebLinks.imprint("https://w/", true))
        assertEquals("https://w/en/privacy/", WebLinks.privacy("https://w", false))
        assertEquals("https://w/datenschutz/", WebLinks.privacy("https://w", true))
        assertEquals("https://w/en/", WebLinks.home("https://w", false))
        assertEquals("https://w/", WebLinks.home("https://w", true))
    }
}
