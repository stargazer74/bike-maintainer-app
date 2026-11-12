package de.bikemaintainer.app.ui.register

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.data.repository.AuthRepository
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RegisterError {
    EMAIL_REQUIRED,
    PASSWORD_LENGTH,
    PASSWORD_TOO_LONG,
    PASSWORD_MISMATCH,
    INVALID,
    CAPTCHA,
    MAIL_QUOTA,
    TOO_MANY_ATTEMPTS,
    NOT_REACHABLE,
    CLEARTEXT_NOT_ALLOWED,
    UNKNOWN,
}

data class RegisterUiState(
    val email: String = "",
    val password: String = "",
    val passwordRepeat: String = "",
    /** Solving the challenge and sending; takes a few seconds. */
    val working: Boolean = false,
    val error: RegisterError? = null,
    /** Address the confirmation email went to, once the registration was accepted. */
    val sentTo: String? = null,
    val resending: Boolean = false,
    val resent: Boolean = false,
    /** Public website of the server (privacy policy link); none without one. */
    val websiteUrl: String? = null,
) {
    /** Without email and passwords, so a logged or printed state never leaks them. */
    override fun toString(): String =
        "RegisterUiState(email=${mask(email)}, password=${mask(password)}, passwordRepeat=${mask(passwordRepeat)}, " +
            "working=$working, error=$error, sentTo=${sentTo?.let(::mask)}, resending=$resending, resent=$resent, " +
            "websiteUrl=$websiteUrl)"

    private fun mask(value: String) = if (value.isEmpty()) "" else "***"
}

/** Limits of the API spec (`NewPassword`); 72 bytes is the BCrypt limit, checked by the backend as well. */
private const val MIN_PASSWORD_LENGTH = 10
private const val MAX_PASSWORD_LENGTH = 72
private const val MAX_PASSWORD_BYTES = 72

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_TOO_MANY_REQUESTS = 429

/** First problem with the input as in the web app, or null if it can be sent. */
internal fun registerInputError(email: String, password: String, repeat: String): RegisterError? = when {
    email.isBlank() -> RegisterError.EMAIL_REQUIRED
    password.length !in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH -> RegisterError.PASSWORD_LENGTH
    password.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES -> RegisterError.PASSWORD_TOO_LONG
    password != repeat -> RegisterError.PASSWORD_MISMATCH
    else -> null
}

/**
 * Portal: creates an account, then asks to confirm the email address. The server answers the same for new and already
 * registered addresses, so the result never reveals whether an account exists. Passwords live only in this view model's
 * memory (no SavedStateHandle) and are dropped once the registration is accepted.
 */
class RegisterViewModel(
    private val auth: AuthRepository,
    private val serverUrl: String,
) : ViewModel() {
    private val _state = MutableStateFlow(RegisterUiState())
    val state: StateFlow<RegisterUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            (auth.fetchConfig(serverUrl) as? Outcome.Success)?.let { config ->
                _state.update { it.copy(websiteUrl = config.value.websiteUrl) }
            }
        }
    }

    fun onEmailChange(email: String) = _state.update { it.copy(email = email, error = null) }

    fun onPasswordChange(password: String) = _state.update { it.copy(password = password, error = null) }

    fun onPasswordRepeatChange(repeat: String) = _state.update { it.copy(passwordRepeat = repeat, error = null) }

    /** [german]: language of the app, used for the account and its confirmation email. */
    fun onSubmit(german: Boolean) {
        val current = _state.value
        if (current.working || current.sentTo != null) return
        val email = current.email.trim()
        registerInputError(email, current.password, current.passwordRepeat)?.let { error ->
            _state.update { it.copy(error = error) }
            return
        }
        _state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            val result = auth.register(serverUrl, email, current.password, german)
            _state.update {
                when (result) {
                    is Outcome.Success -> it.copy(working = false, password = "", passwordRepeat = "", sentTo = email)
                    is Outcome.Failure -> it.copy(working = false, error = result.error.toRegisterError())
                }
            }
        }
    }

    fun onResend() {
        val email = _state.value.sentTo ?: return
        if (_state.value.resending) return
        _state.update { it.copy(resending = true, resent = false, error = null) }
        viewModelScope.launch {
            val result = auth.resendVerification(serverUrl, email)
            _state.update {
                when (result) {
                    is Outcome.Success -> it.copy(resending = false, resent = true)
                    is Outcome.Failure -> it.copy(resending = false, error = result.error.toRegisterError())
                }
            }
        }
    }

    private fun AppError.toRegisterError(): RegisterError = when (this) {
        AppError.Network -> RegisterError.NOT_REACHABLE
        AppError.CleartextNotAllowed -> RegisterError.CLEARTEXT_NOT_ALLOWED
        is AppError.Http -> when {
            status == HTTP_BAD_REQUEST && code == "CAPTCHA_INVALID" -> RegisterError.CAPTCHA
            status == HTTP_BAD_REQUEST -> RegisterError.INVALID
            status == HTTP_TOO_MANY_REQUESTS && code == "MAIL_QUOTA_EXCEEDED" -> RegisterError.MAIL_QUOTA
            status == HTTP_TOO_MANY_REQUESTS -> RegisterError.TOO_MANY_ATTEMPTS
            else -> RegisterError.UNKNOWN
        }
        else -> RegisterError.UNKNOWN
    }
}
