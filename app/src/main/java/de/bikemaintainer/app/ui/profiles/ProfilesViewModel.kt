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

enum class CreateProfileError { NAME_REQUIRED, NAME_TAKEN, FAILED }

data class ProfilesUiState(
    val profiles: List<Profile> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    /** Id of the profile whose selection is in flight. */
    val selecting: Long? = null,
    val error: ProfilesError? = null,
    /** Set once after a successful selection; the NavHost navigates on it. */
    val done: Boolean = false,
    /** Name typed for a new person. */
    val newName: String = "",
    /** The form for a new person is open (always shown while there is no person yet). */
    val creatingOpen: Boolean = false,
    val creating: Boolean = false,
    val createError: CreateProfileError? = null,
)

private const val HTTP_CONFLICT = 409
private const val MAX_NAME = 50

/**
 * Household mode: lists the password-less profiles and signs in as the chosen one. A new person is created here too and
 * selected right away.
 */
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

    fun openCreate() = _state.update { it.copy(creatingOpen = true, createError = null) }

    fun onNewNameChange(name: String) = _state.update { it.copy(newName = name.take(MAX_NAME), createError = null) }

    fun create() {
        val current = _state.value
        if (current.creating || current.selecting != null || current.done) return
        val name = current.newName.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(createError = CreateProfileError.NAME_REQUIRED) }
            return
        }
        _state.update { it.copy(creating = true, createError = null, error = null) }
        viewModelScope.launch {
            when (val result = auth.createProfile(serverUrl, name)) {
                is Outcome.Success -> {
                    _state.update {
                        it.copy(creating = false, newName = "", creatingOpen = false, profiles = it.profiles + result.value)
                    }
                    select(result.value.id)
                }
                is Outcome.Failure -> {
                    val error = result.error
                    val taken = error is AppError.Http && error.status == HTTP_CONFLICT
                    _state.update {
                        it.copy(creating = false, createError = if (taken) CreateProfileError.NAME_TAKEN else CreateProfileError.FAILED)
                    }
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
