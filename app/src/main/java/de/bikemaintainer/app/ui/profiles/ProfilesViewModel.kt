package de.bikemaintainer.app.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.data.repository.AuthRepository
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ProfilesError {
    /** The selection failed on the server or the network; the user picks again. */
    SELECT_FAILED,

    /** The server accepted the selection, but it could not be stored on this device. */
    STORAGE,
}

data class ProfilesUiState(
    val profiles: List<Profile> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    /** Id of the profile whose selection is in flight. */
    val selecting: Long? = null,
    val error: ProfilesError? = null,
    /** Set once after a successful selection; the NavHost navigates on it. */
    val done: Boolean = false,
)

/** Household mode: lists the password-less profiles and signs in as the chosen one. */
class ProfilesViewModel(
    private val auth: AuthRepository,
    private val serverUrl: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfilesUiState())
    val state: StateFlow<ProfilesUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun reload() {
        _state.update { it.copy(loading = true, loadFailed = false) }
        load()
    }

    fun select(id: Long) {
        val current = _state.value
        if (current.selecting != null || current.done) return
        _state.update { it.copy(selecting = id, error = null) }
        viewModelScope.launch {
            when (val result = auth.selectProfile(serverUrl, id)) {
                is Outcome.Success -> _state.update { it.copy(selecting = null, done = true) }
                is Outcome.Failure -> {
                    val error = if (result.error == AppError.Storage) ProfilesError.STORAGE else ProfilesError.SELECT_FAILED
                    _state.update { it.copy(selecting = null, error = error) }
                    // The profile was deleted in the meantime: show the current list.
                    if (result.error == AppError.NotFound) load()
                }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            when (val result = auth.profiles(serverUrl)) {
                is Outcome.Success -> _state.update {
                    it.copy(profiles = result.value, loading = false, loadFailed = false)
                }
                is Outcome.Failure -> _state.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }
}
