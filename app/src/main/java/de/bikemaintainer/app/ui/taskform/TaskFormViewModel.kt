package de.bikemaintainer.app.ui.taskform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bikemaintainer.app.data.repository.MaintenanceRepository
import de.bikemaintainer.app.domain.AccessSource
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.Outcome
import de.bikemaintainer.app.domain.TaskDraft
import de.bikemaintainer.app.domain.TaskField
import de.bikemaintainer.app.domain.isSubscriptionRequired
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskFormUiState(
    val editing: Boolean,
    /** `null` while an existing task is loading (or failed to load, see [loadError]). */
    val draft: TaskDraft? = null,
    val loadError: AppError? = null,
    val showErrors: Boolean = false,
    val saving: Boolean = false,
    val saveError: AppError? = null,
    /** The task is saved; the screen leaves once. */
    val saved: Boolean = false,
)

/** Creates a maintenance task of [vehicleId] ([taskId] `null`) or edits one, including switching it on or off. */
class TaskFormViewModel(
    private val vehicleId: Long,
    private val taskId: Long?,
    private val maintenance: MaintenanceRepository,
    private val accessSource: AccessSource = AccessSource.Unrestricted,
) : ViewModel() {
    private val _state = MutableStateFlow(TaskFormUiState(editing = taskId != null, draft = if (taskId == null) TaskDraft() else null))
    val state: StateFlow<TaskFormUiState> = _state.asStateFlow()

    init {
        if (taskId != null) load(taskId)
    }

    fun retryLoad() {
        if (taskId != null && _state.value.draft == null) load(taskId)
    }

    private fun load(id: Long) {
        _state.update { it.copy(loadError = null) }
        viewModelScope.launch {
            when (val result = maintenance.task(vehicleId, id)) {
                is Outcome.Success -> _state.update { it.copy(draft = TaskDraft.of(result.value)) }
                is Outcome.Failure -> _state.update { it.copy(loadError = result.error) }
            }
        }
    }

    fun invalidFields(): Set<TaskField> {
        val s = _state.value
        return if (s.showErrors) s.draft?.invalidFields().orEmpty() else emptySet()
    }

    fun edit(change: (TaskDraft) -> TaskDraft) = _state.update { s ->
        if (s.saving || s.saved || s.draft == null) s else s.copy(draft = change(s.draft), saveError = null)
    }

    fun save() {
        val s = _state.value
        if (s.saving || s.saved) return
        val input = s.draft?.toInput()
        if (input == null) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        _state.update { it.copy(saving = true, saveError = null, showErrors = true) }
        viewModelScope.launch {
            val result = if (taskId == null) {
                maintenance.createTask(vehicleId, input)
            } else {
                maintenance.updateTask(vehicleId, taskId, input)
            }
            when (result) {
                is Outcome.Success -> _state.update { it.copy(saving = false, saved = true) }
                is Outcome.Failure -> {
                    if (result.error.isSubscriptionRequired()) accessSource.subscriptionRequired()
                    _state.update { it.copy(saving = false, saveError = result.error) }
                }
            }
        }
    }
}
