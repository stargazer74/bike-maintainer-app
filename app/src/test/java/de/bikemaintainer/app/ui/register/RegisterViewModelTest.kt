package de.bikemaintainer.app.ui.register

import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.ServerConfig
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.ui.FakeAuthRepository
import de.bikemaintainer.app.ui.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RegisterViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val auth = FakeAuthRepository()

    private fun filled(): RegisterViewModel = RegisterViewModel(auth, "https://h/").apply {
        onEmailChange(" max@example.org ")
        onPasswordChange("geheim-1234")
        onPasswordRepeatChange("geheim-1234")
    }

    @Test fun validatesLikeTheWebApp() {
        assertEquals(RegisterError.EMAIL_REQUIRED, registerInputError(" ", "geheim-1234", "geheim-1234"))
        assertEquals(RegisterError.PASSWORD_LENGTH, registerInputError("a@b.de", "kurz", "kurz"))
        assertEquals(RegisterError.PASSWORD_LENGTH, registerInputError("a@b.de", "x".repeat(73), "x".repeat(73)))
        // 40 umlauts: 40 characters, but 80 bytes – over the BCrypt limit.
        assertEquals(RegisterError.PASSWORD_TOO_LONG, registerInputError("a@b.de", "ä".repeat(40), "ä".repeat(40)))
        assertEquals(RegisterError.PASSWORD_MISMATCH, registerInputError("a@b.de", "geheim-1234", "geheim-1235"))
        assertNull(registerInputError("a@b.de", "geheim-1234", "geheim-1234"))
    }

    @Test fun invalidInputIsNotSent() {
        val vm = RegisterViewModel(auth, "https://h/")
        vm.onSubmit(german = true)
        assertEquals(RegisterError.EMAIL_REQUIRED, vm.state.value.error)
        assertTrue(auth.registerCalls.isEmpty())
    }

    @Test fun acceptedRegistrationAsksToConfirmAndForgetsThePasswords() {
        val vm = filled()
        vm.onSubmit(german = true)
        assertEquals(listOf<Any>("https://h/", "max@example.org", "geheim-1234", true), auth.registerCalls.single())
        val state = vm.state.value
        assertEquals("max@example.org", state.sentTo)
        assertEquals("", state.password)
        assertEquals("", state.passwordRepeat)
        assertFalse(state.working)
        assertFalse(state.toString().contains("geheim") || state.toString().contains("max@"))
    }

    @Test fun showsWorkingWhileTheChallengeIsSolved() {
        auth.registerGate = CompletableDeferred()
        val vm = filled()
        vm.onSubmit(german = false)
        assertTrue(vm.state.value.working)
        vm.onSubmit(german = false) // ignored while working
        assertEquals(1, auth.registerCalls.size)
        auth.registerGate!!.complete(Unit)
        assertFalse(vm.state.value.working)
    }

    @Test fun mapsServerErrors() {
        val cases = mapOf(
            AppError.Http(400, "CAPTCHA_INVALID") to RegisterError.CAPTCHA,
            AppError.Http(400, "VALIDATION_FAILED") to RegisterError.INVALID,
            AppError.Http(429, "MAIL_QUOTA_EXCEEDED") to RegisterError.MAIL_QUOTA,
            AppError.Http(429, null) to RegisterError.TOO_MANY_ATTEMPTS,
            AppError.Network to RegisterError.NOT_REACHABLE,
            AppError.CleartextNotAllowed to RegisterError.CLEARTEXT_NOT_ALLOWED,
            AppError.Http(500, null) to RegisterError.UNKNOWN,
        )
        for ((error, expected) in cases) {
            auth.registerResult = Outcome.Failure(error)
            val vm = filled()
            vm.onSubmit(german = true)
            assertEquals("$error", expected, vm.state.value.error)
            assertNull(vm.state.value.sentTo)
        }
    }

    @Test fun resendsTheConfirmationEmail() {
        val vm = filled()
        vm.onSubmit(german = true)
        vm.onResend()
        assertEquals(listOf("https://h/", "max@example.org"), auth.resendCalls.single())
        assertTrue(vm.state.value.resent)
        auth.resendResult = Outcome.Failure(AppError.Http(429, "MAIL_QUOTA_EXCEEDED"))
        vm.onResend()
        assertEquals(RegisterError.MAIL_QUOTA, vm.state.value.error)
        assertFalse(vm.state.value.resent)
    }

    @Test fun knowsTheWebsiteForThePrivacyLink() {
        auth.fetchConfigResult = Outcome.Success(ServerConfig(ServerMode.PORTAL, "https://www.example.org"))
        assertEquals("https://www.example.org", RegisterViewModel(auth, "https://h/").state.value.websiteUrl)
    }
}
