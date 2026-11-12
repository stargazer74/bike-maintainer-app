package de.bikemaintainer.app.ui.vehicles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.VehicleSummary
import de.bikemaintainer.app.ui.common.AppCard
import de.bikemaintainer.app.ui.common.ErrorText
import de.bikemaintainer.app.ui.common.LoadingIndicator
import de.bikemaintainer.app.ui.common.ReadOnlyNotice
import de.bikemaintainer.app.ui.common.StatusDot
import de.bikemaintainer.app.ui.common.loadMessageRes
import de.bikemaintainer.app.ui.common.mileageText
import de.bikemaintainer.app.ui.common.nameRes
import de.bikemaintainer.app.ui.common.statusColor

@Composable
fun VehiclesScreen(
    viewModel: VehiclesViewModel,
    onOpenVehicle: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onAddVehicle: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    // Reload when returning to the screen or the app; the ViewModel skips the first resume (it loaded in init).
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose {}
    }

    VehiclesContent(
        state = state,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::refresh,
        onOpenVehicle = onOpenVehicle,
        onOpenSettings = onOpenSettings,
        onAddVehicle = onAddVehicle,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehiclesContent(
    state: VehiclesUiState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onOpenVehicle: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onAddVehicle: () -> Unit = {},
) {
    val showFab = state.access.canWrite && !state.summaries.isNullOrEmpty()
    Scaffold(
        floatingActionButton = {
            if (showFab) {
                ExtendedFloatingActionButton(onClick = onAddVehicle) { Text(stringResource(R.string.vehicle_add)) }
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding),
        ) {
            val summaries = state.summaries
            val error = state.error
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                // Room below the last card, so the button does not cover it.
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (showFab) 88.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    summaries == null && error != null -> item {
                        Column(
                            Modifier.fillParentMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ErrorText(stringResource(error.loadMessageRes()), Modifier.padding(bottom = 8.dp))
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                        }
                    }
                    summaries == null -> item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    }
                    summaries.isEmpty() -> item {
                        Column(
                            Modifier.fillParentMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(R.string.vehicles_empty),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (state.access.canWrite) {
                                Button(onClick = onAddVehicle, modifier = Modifier.padding(top = 16.dp)) {
                                    Text(stringResource(R.string.vehicle_add_first))
                                }
                            } else {
                                ReadOnlyNotice(state.access, Modifier.padding(top = 16.dp))
                            }
                        }
                    }
                    else -> {
                        if (error != null) {
                            item { ErrorText(stringResource(error.loadMessageRes())) }
                        }
                        items(summaries, key = { it.vehicle.id }) { summary ->
                            VehicleCard(summary, onClick = { onOpenVehicle(summary.vehicle.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleCard(summary: VehicleSummary, onClick: () -> Unit) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(summary.vehicle.name, style = MaterialTheme.typography.titleMedium)
            Text(
                mileageText(summary.vehicle.currentMileage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusLine(summary)
        }
    }
}

@Composable
private fun StatusLine(summary: VehicleSummary) {
    val status = summary.status
    // "All up to date" is the friendlier wording for OK (the overall status never has due tasks then).
    val text = when (status) {
        null -> stringResource(R.string.vehicle_no_tasks)
        TaskStatus.OK, TaskStatus.DONE -> stringResource(R.string.vehicle_all_up_to_date)
        else -> stringResource(status.nameRes())
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // The visible text carries the status; the dot is decoration only.
        StatusDot(status.statusColor())
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (summary.dueCount > 0) {
            Text(
                stringResource(R.string.vehicle_due_count, summary.dueCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
