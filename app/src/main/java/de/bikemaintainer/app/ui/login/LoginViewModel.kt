package de.bikemaintainer.app.ui.login

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

enum class LoginError {
    REQUIRED,
    INVALID_CREDENTIALS,
    ACCOUNT_DISABLED,
    EMAIL_NOT_VERIFIED,
    TOO_MANY_ATTEMPTS,
    NOT_REACHABLE,
    CLEARTEXT_NOT_ALLOWED,
    UNKNOWN,
}

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val deviceName: String,
    val loading: Boolean = false,
    val error: LoginError? = null,
    val reloginHint: Boolean,
    /** Set once after a successful login; the NavHost navigates on it. */
    val done: Boolean = false,
)

private const val MAX_DEVICE_NAME = 100

/**
 * Device login against the portal. The password lives only in this view model's memory: no SavedStateHandle (it would
 * survive process death in a Bundle), cleared after every attempt, never logged.
 */
class LoginViewModel(
    private val auth: AuthRepository,
    private val serverUrl: String,
    defaultDeviceName: String,
    relogin: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState(deviceName = defaultDeviceName, reloginHint = relogin))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmailChange(email: String) = _state.update { it.copy(email = email, error = null) }

    fun onPasswordChange(password: String) = _state.update { it.copy(password = password, error = null) }

    fun onDeviceNameChange(name: String) = _state.update { it.copy(deviceName = name.take(MAX_DEVICE_NAME), error = null) }

    fun onSubmit() {
        val current = _state.value
        if (current.loading || current.done) return
        val email = current.email.trim()
        val deviceName = current.deviceName.trim()
        if (email.isEmpty() || current.password.isEmpty() || deviceName.isEmpty()) {
            _state.update { it.copy(error = LoginError.REQUIRED) }
            return
        }
        val password = current.password
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = auth.deviceLogin(serverUrl, email, password, deviceName)
            _state.update {
                when (result) {
                    is Outcome.Success -> it.copy(loading = false, password = "", done = true)
                    is Outcome.Failure -> it.copy(loading = false, password = "", error = result.error.toLoginError())
                }
            }
        }
    }

    private fun AppError.toLoginError(): LoginError = when (this) {
        AppError.Unauthorized -> LoginError.INVALID_CREDENTIALS
        AppError.Network -> LoginError.NOT_REACHABLE
        AppError.CleartextNotAllowed -> LoginError.CLEARTEXT_NOT_ALLOWED
        is AppError.Http -> when {
            status == 401 -> LoginError.INVALID_CREDENTIALS
            status == 403 && code == "ACCOUNT_DISABLED" -> LoginError.ACCOUNT_DISABLED
            status == 403 && code == "EMAIL_NOT_VERIFIED" -> LoginError.EMAIL_NOT_VERIFIED
            status == 429 -> LoginError.TOO_MANY_ATTEMPTS
            else -> LoginError.UNKNOWN
        }
        else -> LoginError.UNKNOWN
    }
}
