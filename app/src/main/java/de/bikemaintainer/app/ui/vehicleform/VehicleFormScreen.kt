package de.bikemaintainer.app.ui.vehicleform

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.VehicleDraft
import de.bikemaintainer.app.domain.VehicleField
import de.bikemaintainer.app.domain.VehicleType
import de.bikemaintainer.app.ui.common.ErrorText
import de.bikemaintainer.app.ui.common.FormScaffold
import de.bikemaintainer.app.ui.common.FormTextField
import de.bikemaintainer.app.ui.common.saveMessageRes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** [onDelete] (only when editing) runs after the user confirmed; the vehicle detail then deletes and leaves. */
@Composable
fun VehicleFormScreen(
    viewModel: VehicleFormViewModel,
    onBack: () -> Unit,
    onSaved: (id: Long, offerTemplate: Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    val currentOnSaved by rememberUpdatedState(onSaved)
    LaunchedEffect(state.saved) { state.saved?.let { currentOnSaved(it, state.offerTemplate) } }
    VehicleFormContent(
        state = state,
        today = viewModel.today,
        onBack = onBack,
        onRetry = viewModel::retryLoad,
        onEdit = viewModel::edit,
        onSave = viewModel::save,
        onDelete = onDelete,
    )
}

@Composable
fun VehicleFormContent(
    state: VehicleFormUiState,
    today: LocalDate,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEdit: ((VehicleDraft) -> VehicleDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val title = when {
        state.readOnly -> R.string.vehicle_form_view
        state.editing -> R.string.vehicle_form_edit
        else -> R.string.vehicle_form_new
    }
    FormScaffold(
        title = stringResource(title),
        onBack = onBack,
        loading = state.draft == null,
        loadError = state.loadError,
        onRetry = onRetry,
    ) {
        val draft = state.draft ?: return@FormScaffold
        val invalid = if (state.showErrors) draft.invalidFields(today) else emptySet()
        val enabled = !state.saving && state.saved == null && !state.readOnly
        if (state.readOnly) {
            Text(
                stringResource(R.string.vehicle_form_read_only),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FormTextField(
            value = draft.name,
            onValueChange = { v -> onEdit { it.copy(name = v) } },
            label = stringResource(R.string.vehicle_form_name),
            error = if (VehicleField.NAME in invalid) stringResource(R.string.vehicle_form_error_name) else null,
            enabled = enabled,
        )
        TypeChoice(draft.type, enabled) { t -> onEdit { it.copy(type = t) } }
        FormTextField(
            value = draft.make,
            onValueChange = { v -> onEdit { it.copy(make = v) } },
            label = stringResource(R.string.vehicle_form_make),
            error = if (VehicleField.MAKE in invalid) stringResource(R.string.vehicle_form_error_too_long) else null,
            enabled = enabled,
        )
        FormTextField(
            value = draft.model,
            onValueChange = { v -> onEdit { it.copy(model = v) } },
            label = stringResource(R.string.vehicle_form_model),
            error = if (VehicleField.MODEL in invalid) stringResource(R.string.vehicle_form_error_too_long) else null,
            enabled = enabled,
        )
        FormTextField(
            value = draft.modelYear,
            onValueChange = { v -> onEdit { it.copy(modelYear = v.filter(Char::isDigit).take(4)) } },
            label = stringResource(R.string.vehicle_form_model_year),
            error = if (VehicleField.MODEL_YEAR in invalid) {
                stringResource(R.string.vehicle_form_error_model_year, VehicleDraft.MIN_YEAR, today.year + 1)
            } else {
                null
            },
            enabled = enabled,
            number = true,
        )
        RegistrationDate(draft.firstRegistrationDate, enabled) { d -> onEdit { it.copy(firstRegistrationDate = d) } }
        FormTextField(
            value = draft.currentMileage,
            onValueChange = { v -> onEdit { it.copy(currentMileage = v) } },
            label = stringResource(R.string.mileage_label),
            error = if (VehicleField.MILEAGE in invalid) stringResource(R.string.error_mileage_invalid) else null,
            enabled = enabled,
            number = true,
        )
        if (state.templateHint && !state.readOnly) {
            Text(
                stringResource(R.string.template_form_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        state.saveError?.let { ErrorText(stringResource(it.saveMessageRes())) }
        if (!state.readOnly) {
            Button(onClick = onSave, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.save))
            }
        }
        if (state.editing && onDelete != null) {
            DeleteVehicle(state.draft.name, enabled = !state.saving && state.saved == null, onDelete = onDelete)
        }
    }
}

/** Kept off the vehicle page on purpose: deleting is rare and cannot be undone, so it asks first. */
@Composable
private fun DeleteVehicle(name: String, enabled: Boolean, onDelete: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(
        onClick = { confirm = true },
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp),
    ) { Text(stringResource(R.string.vehicle_delete)) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.vehicle_delete_confirm_title)) },
            text = { Text(stringResource(R.string.vehicle_delete_confirm_text, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@StringRes
fun VehicleType.labelRes(): Int = when (this) {
    VehicleType.MOTORCYCLE -> R.string.vehicle_type_motorcycle
    VehicleType.CAR -> R.string.vehicle_type_car
    VehicleType.BICYCLE -> R.string.vehicle_type_bicycle
    VehicleType.E_BIKE -> R.string.vehicle_type_e_bike
    VehicleType.QUAD -> R.string.vehicle_type_quad
    VehicleType.SCOOTER -> R.string.vehicle_type_scooter
}

/** The six types as radio buttons: all visible at once, one TalkBack stop each. */
@Composable
private fun TypeChoice(selected: VehicleType, enabled: Boolean, onSelect: (VehicleType) -> Unit) {
    Column(Modifier.selectableGroup()) {
        Text(stringResource(R.string.vehicle_form_type), style = MaterialTheme.typography.titleSmall)
        VehicleType.entries.forEach { type ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = type == selected, enabled = enabled, role = Role.RadioButton) { onSelect(type) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = type == selected, onClick = null, enabled = enabled)
                Text(stringResource(type.labelRes()), Modifier.padding(start = 12.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegistrationDate(date: LocalDate, enabled: Boolean, onDate: (LocalDate) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val label = stringResource(R.string.vehicle_form_first_registration)
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("$label: ${DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)}")
    }
    if (open) {
        // The picker works in UTC milliseconds; any day, like the web form.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                        open = false
                    },
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = pickerState) }
    }
}
