package de.bikemaintainer.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.data.repository.AuthRepository
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.ui.navigation.LoginRoute
import de.bikemaintainer.app.ui.navigation.ProfilesRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupError { INVALID_ADDRESS, NOT_REACHABLE, NOT_BIKE_MAINTAINER, CLEARTEXT_NOT_ALLOWED }

data class SetupUiState(
    val address: String = "",
    val loading: Boolean = false,
    val error: SetupError? = null,
    val confirmCleartext: Boolean = false,
    /** Navigation target (a route object); the NavHost navigates and calls [SetupViewModel.onNavigated]. */
    val next: Any? = null,
)

/** The online account: the portal's address is built in, the user never types it. */
const val PORTAL_SERVER = "https://app.bike-maintainer.de/"

private const val HTTP_SERVER_ERROR = 500

/**
 * Own server: checks the typed or scanned address; nothing is stored here, that happens at login / profile selection.
 */
class SetupViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    /** The cleartext address the user has already confirmed (asked once per input). */
    private var confirmedCleartext: String? = null

    fun onAddressChange(address: String) {
        _state.update { it.copy(address = address, error = null) }
    }

    fun onContinue() {
        if (_state.value.loading) return
        val url = ServerAddress.normalize(_state.value.address)
        if (url == null) {
            _state.update { it.copy(error = SetupError.INVALID_ADDRESS) }
            return
        }
        if (ServerAddress.isCleartext(url) && url != confirmedCleartext) {
            _state.update { it.copy(error = null, confirmCleartext = true) }
            return
        }
        connect(url)
    }

    /** An address read from a QR code ([ServerQr]): taken over and checked right away, like a typed one. */
    fun onScanned(address: String) {
        _state.update { it.copy(address = address, error = null) }
        onContinue()
    }

    fun onCleartextConfirmed() {
        _state.update { it.copy(confirmCleartext = false) }
        val url = ServerAddress.normalize(_state.value.address) ?: return
        confirmedCleartext = url
        if (!_state.value.loading) connect(url)
    }

    fun onCleartextDismissed() {
        _state.update { it.copy(confirmCleartext = false) }
    }

    fun onNavigated() {
        _state.update { it.copy(next = null) }
    }

    private fun connect(url: String) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = auth.fetchConfig(url)
            _state.update {
                when (result) {
                    is Outcome.Success -> it.copy(
                        loading = false,
                        next = if (result.value.mode == ServerMode.HOUSEHOLD) ProfilesRoute(url) else LoginRoute(url),
                    )
                    is Outcome.Failure -> it.copy(loading = false, error = result.error.toSetupError())
                }
            }
        }
    }

    private fun AppError.toSetupError(): SetupError = when (this) {
        AppError.Network -> SetupError.NOT_REACHABLE
        AppError.CleartextNotAllowed -> SetupError.CLEARTEXT_NOT_ALLOWED
        AppError.InvalidAddress -> SetupError.INVALID_ADDRESS
        // A server error (or a proxy whose backend is down) says nothing about what runs there.
        is AppError.Http -> if (status >= HTTP_SERVER_ERROR) SetupError.NOT_REACHABLE else SetupError.NOT_BIKE_MAINTAINER
        else -> SetupError.NOT_BIKE_MAINTAINER
    }
}
