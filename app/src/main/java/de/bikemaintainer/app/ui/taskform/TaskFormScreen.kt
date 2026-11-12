package de.bikemaintainer.app.ui.taskform

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.TaskDraft
import de.bikemaintainer.app.domain.TaskField
import de.bikemaintainer.app.ui.common.ErrorText
import de.bikemaintainer.app.ui.common.FormScaffold
import de.bikemaintainer.app.ui.common.FormTextField
import de.bikemaintainer.app.ui.common.SwitchRow
import de.bikemaintainer.app.ui.common.saveMessageRes

@Composable
fun TaskFormScreen(viewModel: TaskFormViewModel, onBack: () -> Unit, onSaved: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val currentOnSaved by rememberUpdatedState(onSaved)
    LaunchedEffect(state.saved) { if (state.saved) currentOnSaved() }
    TaskFormContent(state, onBack, viewModel::retryLoad, viewModel::edit, viewModel::save)
}

@Composable
fun TaskFormContent(
    state: TaskFormUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEdit: ((TaskDraft) -> TaskDraft) -> Unit,
    onSave: () -> Unit,
) {
    FormScaffold(
        title = stringResource(if (state.editing) R.string.task_form_edit else R.string.task_form_new),
        onBack = onBack,
        loading = state.draft == null,
        loadError = state.loadError,
        onRetry = onRetry,
        notFound = R.string.error_task_not_found,
    ) {
        val draft = state.draft ?: return@FormScaffold
        val invalid = if (state.showErrors) draft.invalidFields() else emptySet()
        val enabled = !state.saving && !state.saved
        val numberError = stringResource(R.string.task_form_error_number)
        FormTextField(
            value = draft.name,
            onValueChange = { v -> onEdit { it.copy(name = v) } },
            label = stringResource(R.string.task_form_name),
            error = if (TaskField.NAME in invalid) stringResource(R.string.task_form_error_name) else null,
            enabled = enabled,
        )
        FormTextField(
            value = draft.description,
            onValueChange = { v -> onEdit { it.copy(description = v) } },
            label = stringResource(R.string.task_form_description),
            error = if (TaskField.DESCRIPTION in invalid) stringResource(R.string.task_form_error_description) else null,
            enabled = enabled,
            singleLine = false,
        )
        SwitchRow(
            label = stringResource(R.string.task_form_one_time),
            hint = stringResource(R.string.task_form_one_time_hint),
            checked = draft.oneTime,
            enabled = enabled,
            onChange = { v -> onEdit { it.copy(oneTime = v) } },
        )
        Text(
            stringResource(if (draft.oneTime) R.string.task_form_due_hint else R.string.task_form_interval_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!draft.oneTime) {
            FormTextField(
                value = draft.intervalKm,
                onValueChange = { v -> onEdit { it.copy(intervalKm = v) } },
                label = stringResource(R.string.task_form_interval_km),
                error = if (TaskField.INTERVAL_KM in invalid) numberError else null,
                enabled = enabled,
                number = true,
            )
            FormTextField(
                value = draft.intervalMonths,
                onValueChange = { v -> onEdit { it.copy(intervalMonths = v) } },
                label = stringResource(R.string.task_form_interval_months),
                error = if (TaskField.INTERVAL_MONTHS in invalid) numberError else null,
                enabled = enabled,
                number = true,
            )
            if (TaskField.NO_INTERVAL in invalid) ErrorText(stringResource(R.string.task_form_error_no_interval))
        }
        FormTextField(
            value = draft.firstDueKm,
            onValueChange = { v -> onEdit { it.copy(firstDueKm = v) } },
            label = stringResource(R.string.task_form_first_due_km),
            error = if (TaskField.FIRST_DUE_KM in invalid) numberError else null,
            enabled = enabled,
            number = true,
        )
        FormTextField(
            value = draft.firstDueMonths,
            onValueChange = { v -> onEdit { it.copy(firstDueMonths = v) } },
            label = stringResource(R.string.task_form_first_due_months),
            error = if (TaskField.FIRST_DUE_MONTHS in invalid) numberError else null,
            enabled = enabled,
            number = true,
        )
        if (state.editing) {
            SwitchRow(
                label = stringResource(R.string.task_form_active),
                hint = stringResource(R.string.task_form_active_hint),
                checked = draft.active,
                enabled = enabled,
                onChange = { v -> onEdit { it.copy(active = v) } },
            )
        }
        // Editing: a 404 means the task was deleted meanwhile (or its vehicle; the task is gone either way).
        val notFound = if (state.editing) R.string.error_task_not_found else R.string.error_vehicle_not_found
        state.saveError?.let { ErrorText(stringResource(it.saveMessageRes(notFound))) }
        Button(onClick = onSave, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.save))
        }
    }
}
