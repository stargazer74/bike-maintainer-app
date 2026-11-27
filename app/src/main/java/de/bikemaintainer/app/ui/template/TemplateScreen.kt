package de.bikemaintainer.app.ui.template

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.AppError
import de.bikemaintainer.app.domain.TemplateApplyResult
import de.bikemaintainer.app.domain.TemplateSource
import de.bikemaintainer.app.domain.TemplateSourceQuality
import de.bikemaintainer.app.domain.TemplateSummary
import de.bikemaintainer.app.domain.TemplateTask
import de.bikemaintainer.app.domain.TemplateTaskRow
import de.bikemaintainer.app.domain.VehicleTemplate
import de.bikemaintainer.app.domain.isSubscriptionRequired
import de.bikemaintainer.app.domain.openableUrl
import de.bikemaintainer.app.domain.reportUrl
import de.bikemaintainer.app.ui.common.AppCard
import de.bikemaintainer.app.ui.common.ErrorText
import de.bikemaintainer.app.ui.common.FormScaffold
import de.bikemaintainer.app.ui.common.LoadingIndicator
import de.bikemaintainer.app.ui.common.formatNumber
import de.bikemaintainer.app.ui.common.rememberWebLinkOpener
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** [onApplied] runs once, with the result, after the template was applied; the vehicle detail reports it. */
@Composable
fun TemplateScreen(viewModel: TemplateViewModel, onBack: () -> Unit, onApplied: (TemplateApplyResult) -> Unit) {
    val state by viewModel.state.collectAsState()
    val currentOnApplied by rememberUpdatedState(onApplied)
    LaunchedEffect(state.applied) { state.applied?.let(currentOnApplied) }
    val openLink = rememberWebLinkOpener()
    FormScaffold(
        title = stringResource(R.string.template_title),
        onBack = onBack,
        loading = state.vehicle == null,
        loadError = state.loadError,
        onRetry = viewModel::retryLoad,
    ) {
        val template = state.template
        if (template == null) {
            SearchStep(state, viewModel)
        } else {
            ApplyStep(state, template, viewModel, openLink)
        }
        state.problem?.let { ErrorText(stringResource(it.messageRes())) }
    }
}

@StringRes
private fun TemplateProblem.messageRes(): Int = when (this) {
    TemplateProblem.Search -> R.string.template_error_search
    TemplateProblem.Load -> R.string.template_error_load
    is TemplateProblem.Apply -> when {
        error == AppError.Network -> R.string.error_no_connection
        error.isSubscriptionRequired() -> R.string.error_read_only
        error is AppError.Http && error.status == 400 -> R.string.template_error_last_done
        else -> R.string.template_error_apply
    }
}

@Composable
private fun ColumnScope.SearchStep(state: TemplateUiState, viewModel: TemplateViewModel) {
    Text(stringResource(R.string.template_search_intro), style = MaterialTheme.typography.bodyMedium)
    val enabled = !state.loadingTemplate
    OutlinedTextField(
        value = state.make,
        onValueChange = viewModel::onMake,
        label = { Text(stringResource(R.string.template_make)) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.model,
        onValueChange = viewModel::onModel,
        label = { Text(stringResource(R.string.template_model)) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = viewModel::search,
        enabled = state.canSearch && enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(stringResource(R.string.template_search)) }
    if (state.searching || state.loadingTemplate) LoadingIndicator(Modifier.align(Alignment.CenterHorizontally))
    val hits = state.results
    if (hits != null && !state.searching) {
        when {
            hits.isEmpty() -> Text(stringResource(R.string.template_none), style = MaterialTheme.typography.bodyLarge)
            else -> {
                Text(stringResource(R.string.template_pick), style = MaterialTheme.typography.titleMedium)
                hits.forEach { hit -> HitCard(hit, enabled) { viewModel.pick(hit) } }
            }
        }
    }
}

@Composable
private fun HitCard(hit: TemplateSummary, enabled: Boolean, onPick: () -> Unit) {
    OutlinedButton(onClick = onPick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text("${hit.make} ${hit.model}", style = MaterialTheme.typography.titleSmall)
            Text(
                "${yearsText(hit.yearFrom, hit.yearTo)} · " +
                    "${pluralStringResource(R.plurals.template_task_count, hit.taskCount, hit.taskCount)} · " +
                    stringResource(hit.sourceQuality.labelRes()),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun yearsText(from: Int, to: Int?): String =
    if (to != null) stringResource(R.string.template_years, from, to) else stringResource(R.string.template_years_open, from)

@StringRes
private fun TemplateSourceQuality.labelRes(): Int = when (this) {
    TemplateSourceQuality.MANUFACTURER -> R.string.template_quality_manufacturer
    TemplateSourceQuality.SECONDARY -> R.string.template_quality_secondary
}

@Composable
private fun ColumnScope.ApplyStep(
    state: TemplateUiState,
    template: VehicleTemplate,
    viewModel: TemplateViewModel,
    openLink: (String) -> Unit,
) {
    val enabled = !state.applying
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${template.make} ${template.model}", style = MaterialTheme.typography.titleMedium)
        Text(
            "${yearsText(template.yearFrom, template.yearTo)} · ${stringResource(template.sourceQuality.labelRes())}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        template.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        TextButton(onClick = { openLink(reportUrl(template.id)) }) { Text(stringResource(R.string.template_report)) }
    }
    if (state.showLastDone) {
        Text(
            stringResource(R.string.template_overdue_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = viewModel::fillEmptyFields,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.template_fill_empty)) }
    }
    state.rows.forEachIndexed { index, row ->
        TaskRowCard(
            row = row,
            showLastDone = state.showLastDone,
            enabled = enabled,
            error = state.lastDoneError(row),
            vehicleStart = state.vehicle?.firstRegistrationDate,
            today = state.today,
            templateId = template.id,
            onToggle = { viewModel.toggle(index) },
            onKm = { viewModel.onLastDoneKm(index, it) },
            onDate = { viewModel.onLastDoneDate(index, it) },
            openLink = openLink,
        )
    }
    if (template.sources.isNotEmpty()) SourcesSection(template.sources, openLink)

    Text(stringResource(R.string.template_disclaimer), style = MaterialTheme.typography.bodyMedium)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = state.confirmed, enabled = enabled, role = Role.Checkbox, onValueChange = viewModel::onConfirmed),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = state.confirmed, onCheckedChange = null, enabled = enabled)
        Text(stringResource(R.string.template_disclaimer_confirm), Modifier.padding(start = 8.dp))
    }
    Button(
        onClick = viewModel::apply,
        enabled = state.canApply,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        if (state.applying) LoadingIndicator(small = true) else Text(stringResource(R.string.template_apply))
    }
    TextButton(onClick = viewModel::backToSearch, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.template_back_to_search))
    }
}

@Composable
private fun TaskRowCard(
    row: TemplateTaskRow,
    showLastDone: Boolean,
    enabled: Boolean,
    error: LastDoneError?,
    vehicleStart: LocalDate?,
    today: LocalDate,
    templateId: String,
    onToggle: () -> Unit,
    onKm: (String) -> Unit,
    onDate: (LocalDate?) -> Unit,
    openLink: (String) -> Unit,
) {
    val task = row.task
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = row.selected,
                        enabled = enabled && !row.existing,
                        role = Role.Checkbox,
                        onValueChange = { onToggle() },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = row.selected, onCheckedChange = null, enabled = enabled && !row.existing)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(task.name, style = MaterialTheme.typography.titleSmall)
                    if (row.existing) {
                        Text(
                            stringResource(R.string.template_already_present),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(intervalText(task), style = MaterialTheme.typography.bodyMedium)
            task.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (showLastDone && !row.existing) LastDone(row, enabled, error, vehicleStart, today, onKm, onDate)
            if (task.details.isNotEmpty()) TaskDetails(task)
            TextButton(
                onClick = { openLink(reportUrl(templateId, task.key)) },
            ) { Text(stringResource(R.string.template_report_task, task.name)) }
        }
    }
}

@Composable
private fun intervalText(task: TemplateTask): String {
    val months = task.intervalMonths?.takeIf { it > 0 }
    val km = task.intervalKm?.takeIf { it > 0 }?.let { formatNumber(it) }
    return when {
        task.oneTime -> stringResource(R.string.template_interval_one_time)
        km != null && months != null -> stringResource(R.string.template_interval_km_months, km, months.toString())
        km != null -> stringResource(R.string.template_interval_km, km)
        months == 1 -> stringResource(R.string.template_interval_month)
        months != null -> stringResource(R.string.template_interval_months, months.toString())
        else -> stringResource(R.string.template_interval_none)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LastDone(
    row: TemplateTaskRow,
    enabled: Boolean,
    error: LastDoneError?,
    vehicleStart: LocalDate?,
    today: LocalDate,
    onKm: (String) -> Unit,
    onDate: (LocalDate?) -> Unit,
) {
    val name = row.task.name
    val kmLabel = stringResource(R.string.template_last_done_km)
    val dateLabel = stringResource(R.string.template_last_done_date)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.template_last_done), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = row.lastDoneKm?.toString().orEmpty(),
            onValueChange = onKm,
            label = { Text(stringResource(R.string.template_last_done_field, name, kmLabel)) },
            singleLine = true,
            enabled = enabled,
            isError = error == LastDoneError.KM_TOO_HIGH,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error == LastDoneError.KM_TOO_HIGH) ErrorText(stringResource(R.string.template_last_done_km_too_high))
        if (error == LastDoneError.DATE_OUT_OF_RANGE) ErrorText(stringResource(R.string.template_last_done_date_out_of_range))
        var open by rememberSaveable { mutableStateOf(false) }
        val locale = LocalConfiguration.current.locales[0]
        val date = row.lastDoneDate
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                val shown = date?.let { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(it) } ?: dateLabel
                Text(stringResource(R.string.template_last_done_field, name, shown))
            }
            if (date != null) {
                TextButton(onClick = { onDate(null) }, enabled = enabled) {
                    Text(stringResource(R.string.template_last_done_date_none))
                }
            }
        }
        if (open) {
            // The picker works in UTC milliseconds.
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = (date ?: today).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                selectableDates = remember(vehicleStart, today) { lastDoneDates(vehicleStart, today) },
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
}

/** Only days from the first registration up to today can be "last done". */
@OptIn(ExperimentalMaterial3Api::class)
private fun lastDoneDates(start: LocalDate?, today: LocalDate): SelectableDates = object : SelectableDates {
    private val from = start?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli() ?: Long.MIN_VALUE
    private val to = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in from..to

    override fun isSelectableYear(year: Int): Boolean =
        year <= today.year && (start == null || year >= start.year)
}

@Composable
private fun TaskDetails(task: TemplateTask) {
    var expanded by rememberSaveable(task.key) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.template_details)) }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            task.details.forEach { detail ->
                Text(
                    "${detail.label}: ${detail.value}" + detail.quantity?.let { " ($it)" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Where the data comes from; a link is opened in the browser only when it is tapped. */
@Composable
private fun SourcesSection(sources: List<TemplateSource>, openLink: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.template_sources)) }
    if (!expanded) return
    sources.forEach { source ->
        val retrieved = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(source.retrieved)
        val title = listOfNotNull(source.title, source.locator).joinToString(", ")
        Column {
            val url = source.openableUrl()
            if (url != null) {
                TextButton(onClick = { openLink(url) }) { Text(title) }
            } else {
                Text(title, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                stringResource(R.string.template_retrieved, retrieved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
