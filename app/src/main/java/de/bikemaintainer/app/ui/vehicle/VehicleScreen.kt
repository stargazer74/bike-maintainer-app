package de.bikemaintainer.app.ui.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.ui.common.AppCard
import de.bikemaintainer.app.ui.common.ErrorText
import de.bikemaintainer.app.ui.common.LoadingIndicator
import de.bikemaintainer.app.ui.common.StatusDot
import de.bikemaintainer.app.ui.common.formatNumber
import de.bikemaintainer.app.ui.common.isNotFound
import de.bikemaintainer.app.ui.common.mileageText
import de.bikemaintainer.app.ui.common.saveMessageRes
import de.bikemaintainer.app.ui.common.ReadOnlyNotice
import de.bikemaintainer.app.ui.common.statusColor
import de.bikemaintainer.app.ui.common.vehicleLoadMessageRes
import de.bikemaintainer.app.ui.logmaintenance.LogResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun VehicleScreen(
    viewModel: VehicleViewModel,
    onBack: () -> Unit,
    onLogMaintenance: () -> Unit,
    logResult: StateFlow<String?>,
    onLogResultShown: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val result by logResult.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val currentShown by rememberUpdatedState(onLogResultShown)
    // The messages are resolved up front: stringResource is not available inside the effect.
    val saved = stringResource(R.string.log_saved)
    val savedMileageFailed = stringResource(R.string.log_saved_mileage_failed)
    LaunchedEffect(result) {
        // The partial failure asks the user to act (enter the mileage again), so it stays longer.
        val (message, duration) = when (result) {
            LogResult.SAVED.name -> saved to SnackbarDuration.Short
            LogResult.SAVED_MILEAGE_FAILED.name -> savedMileageFailed to SnackbarDuration.Long
            else -> return@LaunchedEffect
        }
        // Consume first (the effect restarts when the value changes, so the snackbar runs in its own scope):
        // the message is shown once even if the screen is recreated while the snackbar is visible.
        currentShown()
        scope.launch { snackbarHost.showSnackbar(message, duration = duration) }
    }

    // Reload when returning to the app; the ViewModel skips the first resume (it loaded in init).
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose {}
    }

    VehicleContent(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::refresh,
        snackbarHost = snackbarHost,
        actions = VehicleActions(
            onOpenMileage = viewModel::openMileageDialog,
            onMileageInput = viewModel::onMileageInput,
            onSaveMileage = viewModel::saveMileage,
            onDismissMileage = viewModel::dismissMileageDialog,
            onLogMaintenance = onLogMaintenance,
        ),
    )
}

data class VehicleActions(
    val onOpenMileage: () -> Unit,
    val onMileageInput: (String) -> Unit,
    val onSaveMileage: () -> Unit,
    val onDismissMileage: () -> Unit,
    val onLogMaintenance: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VehicleContent(
    state: VehicleUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    actions: VehicleActions,
    snackbarHost: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(state.vehicle?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        state.mileageDialog?.let { MileageDialog(it, actions) }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding),
        ) {
            val vehicle = state.vehicle
            val error = state.error
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    vehicle == null && error != null -> item {
                        Column(
                            Modifier.fillParentMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ErrorText(stringResource(error.vehicleLoadMessageRes()), Modifier.padding(bottom = 8.dp))
                            if (error.isNotFound()) {
                                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                            } else {
                                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                            }
                        }
                    }
                    vehicle == null -> item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    }
                    else -> {
                        item { VehicleHeader(vehicle) }
                        item {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (state.access.canWrite) {
                                    OutlinedButton(onClick = actions.onOpenMileage) {
                                        Text(stringResource(R.string.mileage_enter))
                                    }
                                    if (state.tasks.isNotEmpty()) {
                                        Button(onClick = actions.onLogMaintenance) {
                                            Text(stringResource(R.string.log_title))
                                        }
                                    }
                                } else {
                                    ReadOnlyNotice(state.access)
                                }
                            }
                        }
                        if (error != null) item { ErrorText(stringResource(error.vehicleLoadMessageRes())) }
                        if (state.tasks.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.vehicle_tasks_empty),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        items(state.tasks, key = { it.id }) { TaskCard(it) }
                        state.measurements?.takeIf { it.templates.isNotEmpty() }?.let { context ->
                            item(key = "measurements") { LatestMeasurementsCard(context) }
                            item(key = "specification") { SpecificationCard(context.specification) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MileageDialog(dialog: MileageDialogState, actions: VehicleActions) {
    AlertDialog(
        onDismissRequest = actions.onDismissMileage,
        title = { Text(stringResource(R.string.mileage_enter)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dialog.input,
                    onValueChange = actions.onMileageInput,
                    label = { Text(stringResource(R.string.mileage_label)) },
                    singleLine = true,
                    enabled = !dialog.saving,
                    isError = dialog.error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { actions.onSaveMileage() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                when (val error = dialog.error) {
                    null -> {}
                    MileageError.Invalid -> ErrorText(stringResource(R.string.error_mileage_invalid))
                    is MileageError.TooLow ->
                        ErrorText(stringResource(R.string.error_mileage_too_low, formatNumber(error.current)))
                    is MileageError.Save -> ErrorText(stringResource(error.error.saveMessageRes()))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onSaveMileage, enabled = !dialog.saving) {
                if (dialog.saving) LoadingIndicator(small = true) else Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissMileage, enabled = !dialog.saving) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun VehicleHeader(vehicle: Vehicle) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(mileageText(vehicle.currentMileage), style = MaterialTheme.typography.headlineMedium)
        val makeModel = listOfNotNull(vehicle.make, vehicle.model).joinToString(" ")
        Text(
            if (makeModel.isEmpty()) {
                stringResource(R.string.vehicle_subtitle_year, vehicle.modelYear)
            } else {
                stringResource(R.string.vehicle_subtitle, makeModel, vehicle.modelYear)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TaskCard(task: MaintenanceTask) {
    val color = task.status.statusColor()
    // One TalkBack stop per task: name, status text (the dot is decoration only).
    AppCard(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.name, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(color)
                Text(task.label().text(), style = MaterialTheme.typography.bodyMedium, color = color)
            }
            val progress = task.progress
            if (progress != null && task.status != TaskStatus.DONE) {
                LinearProgressIndicator(
                    progress = { progress.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = color,
                )
            }
        }
    }
}
