package de.bikemaintainer.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.data.repository.AuthRepository
import de.bikemaintainer.app.domain.Account
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.ServerMode
import de.bikemaintainer.app.notifications.DueNotifications
import de.bikemaintainer.app.notifications.ReminderScheduler
import de.bikemaintainer.app.notifications.cancelQuietly
import de.bikemaintainer.app.notifications.syncQuietly
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
    /** The reminder switch. */
    val notificationsEnabled: Boolean = true,
    /** The switch is on, but the system does not allow notifications (permission denied or turned off). */
    val notificationsBlocked: Boolean = false,
    /** Route to navigate to once (after logout or switching the server). */
    val next: Any? = null,
)

class SettingsViewModel(
    private val auth: AuthRepository,
    private val reminders: ReminderScheduler,
    private val notifications: DueNotifications,
    private val notificationsAllowed: () -> Boolean,
) : ViewModel() {
    private var lastAllowed = notificationsAllowed()
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            auth.settings.collect { stored ->
                _state.update {
                    it.copy(
                        notificationsEnabled = stored.notificationsEnabled,
                        notificationsBlocked = stored.notificationsEnabled && !notificationsAllowed(),
                    )
                }
            }
        }
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

    /** Saves the switch (the wish of the user, even without permission) and brings the schedule in line. */
    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            auth.setNotificationsEnabled(enabled)
            lastAllowed = notificationsAllowed()
            updateBlocked()
            reminders.syncQuietly()
        }
    }

    /**
     * Re-reads the system permission, e.g. when the screen is resumed or after the permission dialog. If it changed
     * since the last sync (granted in the dialog or the system settings), the schedule is brought in line again.
     */
    fun refreshPermission() {
        updateBlocked()
        val allowed = notificationsAllowed()
        if (allowed != lastAllowed) {
            lastAllowed = allowed
            viewModelScope.launch { reminders.syncQuietly() }
        }
    }

    private fun updateBlocked() {
        _state.update { it.copy(notificationsBlocked = it.notificationsEnabled && !notificationsAllowed()) }
    }

    /** Logs out but keeps the server; the repository clears local credentials even if the server call fails. */
    fun logout() = run {
        val server = _state.value.serverUrl
        auth.logout()
        stopReminders()
        when {
            server == null -> SetupRoute
            _state.value.mode == ServerMode.HOUSEHOLD -> ProfilesRoute(server)
            else -> LoginRoute(server)
        }
    }

    fun switchServer() = run {
        auth.switchServer()
        stopReminders()
        SetupRoute
    }

    /** No more reminders for the account that is gone, and no vehicle names left in the status bar. */
    private fun stopReminders() {
        reminders.cancelQuietly()
        notifications.cancelAll()
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
