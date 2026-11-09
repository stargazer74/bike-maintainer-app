package de.bikemaintainer.app.ui.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.Vehicle
import de.bikemaintainer.app.ui.common.ErrorText
import de.bikemaintainer.app.ui.common.LoadingIndicator
import de.bikemaintainer.app.ui.common.StatusDot
import de.bikemaintainer.app.ui.common.isNotFound
import de.bikemaintainer.app.ui.common.loadMessageRes
import de.bikemaintainer.app.ui.common.mileageText
import de.bikemaintainer.app.ui.common.nameRes
import de.bikemaintainer.app.ui.common.statusColor

@Composable
fun VehicleScreen(viewModel: VehicleViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()

    // Reload when returning to the app (not on the first resume: the ViewModel loaded in init).
    val firstResume = remember { booleanArrayOf(true) }
    LifecycleResumeEffect(Unit) {
        if (firstResume[0]) firstResume[0] = false else viewModel.reloadSilently()
        onPauseOrDispose {}
    }

    VehicleContent(state = state, onBack = onBack, onRefresh = viewModel::refresh, onRetry = viewModel::refresh)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleContent(state: VehicleUiState, onBack: () -> Unit, onRefresh: () -> Unit, onRetry: () -> Unit) {
    Scaffold(
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
                            ErrorText(stringResource(error.loadMessageRes()), Modifier.padding(bottom = 8.dp))
                            if (error.isNotFound()) {
                                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                            } else {
                                TextButton(onClick = onRetry) { Text(stringResource(R.string.profiles_retry)) }
                            }
                        }
                    }
                    vehicle == null -> item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    }
                    else -> {
                        item { VehicleHeader(vehicle) }
                        if (error != null) item { ErrorText(stringResource(error.loadMessageRes())) }
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
                    }
                }
            }
        }
    }
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.name, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(color, description = stringResource(task.status.nameRes()))
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
