package de.bikemaintainer.app.ui.logmaintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.parseMileage
import de.bikemaintainer.app.ui.common.ErrorText
import de.bikemaintainer.app.ui.common.LoadingIndicator
import de.bikemaintainer.app.ui.common.StatusDot
import de.bikemaintainer.app.ui.common.formatNumber
import de.bikemaintainer.app.ui.common.isNotFound
import de.bikemaintainer.app.ui.common.saveMessageRes
import de.bikemaintainer.app.ui.common.statusColor
import de.bikemaintainer.app.ui.common.vehicleLoadMessageRes
import de.bikemaintainer.app.ui.vehicle.label
import de.bikemaintainer.app.ui.vehicle.text
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun LogMaintenanceScreen(
    viewModel: LogMaintenanceViewModel,
    onBack: () -> Unit,
    onDone: (LogResult) -> Unit,
    onScanWorksheet: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val currentOnDone by rememberUpdatedState(onDone)
    LaunchedEffect(state.done) {
        state.done?.let {
            currentOnDone(it)
            viewModel.consumeDone()
        }
    }
    LogMaintenanceContent(
        state = state,
        today = viewModel.latestDate,
        onBack = onBack,
        actions = LogActions(
            onRetry = viewModel::retryLoad,
            onDate = viewModel::onDate,
            onMileage = viewModel::onMileageInput,
            onToggleTask = viewModel::toggleTask,
            onNotes = viewModel::onNotes,
            onRaise = viewModel::onRaiseMileage,
            onSave = viewModel::save,
            measurements = MeasurementActions(
                onRetry = viewModel::retryMeasurements,
                onAdd = viewModel::addTemplate,
                onRemove = viewModel::removeTemplate,
                onLayout = viewModel::changeLayout,
                onValve = viewModel::onValve,
                onApplyShim = viewModel::applyShim,
                onBrakes = viewModel::onBrakes,
                onChain = viewModel::onChain,
                onTires = viewModel::onTires,
                onScan = onScanWorksheet,
            ),
        ),
    )
}

data class LogActions(
    val onRetry: () -> Unit,
    val onDate: (LocalDate) -> Unit,
    val onMileage: (String) -> Unit,
    val onToggleTask: (Long) -> Unit,
    val onNotes: (String) -> Unit,
    val onRaise: (Boolean) -> Unit,
    val onSave: () -> Unit,
    val measurements: MeasurementActions,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogMaintenanceContent(state: LogMaintenanceUiState, today: LocalDate, onBack: () -> Unit, actions: LogActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val loadError = state.loadError
            when {
                loadError != null -> Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ErrorText(stringResource(loadError.vehicleLoadMessageRes()), Modifier.padding(bottom = 8.dp))
                    if (loadError.isNotFound()) {
                        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                    } else {
                        TextButton(onClick = actions.onRetry) { Text(stringResource(R.string.retry)) }
                    }
                }
                state.vehicle == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
                else -> Form(state, today, actions)
            }
        }
    }
}

@Composable
private fun Form(state: LogMaintenanceUiState, today: LocalDate, actions: LogActions) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DateField(state.date, today, enabled = !state.locked, onDate = actions.onDate)
        OutlinedTextField(
            value = state.mileageInput,
            onValueChange = actions.onMileage,
            label = { Text(stringResource(R.string.mileage_label)) },
            singleLine = true,
            enabled = !state.locked,
            isError = state.error == LogError.InvalidMileage,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.offerRaise) {
            val km = parseMileage(state.mileageInput) ?: 0
            CheckRow(
                checked = state.raiseMileage,
                enabled = !state.locked,
                onChange = actions.onRaise,
            ) {
                Text(stringResource(R.string.log_raise_mileage, formatNumber(km)), Modifier.weight(1f))
            }
        }
        Text(stringResource(R.string.log_tasks), style = MaterialTheme.typography.titleMedium)
        state.tasks.forEach { task ->
            TaskRow(task, task.id in state.selected, !state.locked) { actions.onToggleTask(task.id) }
        }
        OutlinedTextField(
            value = state.notes,
            onValueChange = actions.onNotes,
            label = { Text(stringResource(R.string.log_notes)) },
            supportingText = {
                Text(
                    stringResource(R.string.log_notes_counter, state.notes.length),
                    Modifier.fillMaxWidth(),
                )
            },
            minLines = 3,
            enabled = !state.locked,
            modifier = Modifier.fillMaxWidth(),
        )
        MeasurementSection(state, actions.measurements)
        Text(
            stringResource(R.string.log_costs_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (val error = state.error) {
            null -> {}
            LogError.InvalidMileage -> ErrorText(stringResource(R.string.error_mileage_invalid))
            LogError.NoTask -> ErrorText(stringResource(R.string.error_no_task_selected))
            LogError.Measurements -> ErrorText(stringResource(R.string.error_measurements_invalid))
            is LogError.Save -> ErrorText(stringResource(error.error.saveMessageRes()))
        }
        Button(onClick = actions.onSave, enabled = !state.locked, modifier = Modifier.fillMaxWidth()) {
            if (state.locked) LoadingIndicator(small = true) else Text(stringResource(R.string.save))
        }
    }
}

/** A row that toggles as a whole (checkbox, role Checkbox); the checkbox itself is decoration for TalkBack. */
@Composable
internal fun CheckRow(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = onChange,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        content()
    }
}

@Composable
private fun TaskRow(task: MaintenanceTask, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val color = task.status.statusColor()
    CheckRow(checked = checked, enabled = enabled, onChange = { onToggle() }) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(task.name, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(color)
                Text(task.label().text(), style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(date: LocalDate, today: LocalDate, enabled: Boolean, onDate: (LocalDate) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val label = stringResource(R.string.log_date)
    OutlinedButton(
        onClick = { open = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text("$label: ${DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)}")
    }
    if (open) {
        // The picker works in UTC milliseconds; only today and earlier days can be chosen.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = isSelectableLogDate(utcTimeMillis, today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let {
                            onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        open = false
                    },
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = pickerState) }
    }
}

/** The picker works in UTC milliseconds regardless of the default time zone; only [today] and earlier days qualify. */
internal fun isSelectableLogDate(utcMillis: Long, today: LocalDate): Boolean =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate() <= today
