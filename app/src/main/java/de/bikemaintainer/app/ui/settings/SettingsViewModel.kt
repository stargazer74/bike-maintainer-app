package de.bikemaintainer.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.data.repository.AuthRepository
import de.bikemaintainer.app.domain.Account
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.ui.navigation.LoginRoute
import de.bikemaintainer.app.ui.navigation.ProfilesRoute
import de.bikemaintainer.app.ui.navigation.SetupRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String? = null,
    val mode: ServerMode? = null,
    val account: Account? = null,
    val websiteUrl: String? = null,
    val busy: Boolean = false,
    /** Route to navigate to once (after logout or switching the server). */
    val next: Any? = null,
)

class SettingsViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = auth.settings.first()
            _state.update { it.copy(serverUrl = stored.serverUrl, mode = stored.mode) }
            val server = stored.serverUrl ?: return@launch
            (auth.currentAccount() as? Outcome.Success)?.let { r -> _state.update { it.copy(account = r.value) } }
            // Without the config (failure or no website) the website links are simply not shown.
            (auth.fetchConfig(server) as? Outcome.Success)?.let { r ->
                _state.update { it.copy(websiteUrl = r.value.websiteUrl) }
            }
        }
    }

    /** Logs out but keeps the server; the repository clears local credentials even if the server call fails. */
    fun logout() = run {
        val server = _state.value.serverUrl
        auth.logout()
        when {
            server == null -> SetupRoute
            _state.value.mode == ServerMode.HOUSEHOLD -> ProfilesRoute(server)
            else -> LoginRoute(server)
        }
    }

    fun switchServer() = run {
        auth.switchServer()
        SetupRoute
    }

    fun navigated() = _state.update { it.copy(next = null) }

    private fun run(block: suspend () -> Any) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val next = block()
            _state.update { it.copy(busy = false, next = next) }
        }
    }
}
