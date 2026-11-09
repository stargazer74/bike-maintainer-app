package de.bikemaintainer.app.ui.vehicles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.domain.VehicleSummary
import de.bikemaintainer.app.ui.common.ErrorText
import de.bikemaintainer.app.ui.common.LoadingIndicator
import de.bikemaintainer.app.ui.common.loadMessageRes
import de.bikemaintainer.app.ui.common.rememberWebLinkOpener
import de.bikemaintainer.app.ui.theme.LocalStatusColors
import java.text.NumberFormat

@Composable
fun VehiclesScreen(
    viewModel: VehiclesViewModel,
    onOpenVehicle: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val openLink = rememberWebLinkOpener()

    // Reload when returning to the app (not on the first resume: the ViewModel loaded in init).
    val firstResume = remember { booleanArrayOf(true) }
    LifecycleResumeEffect(Unit) {
        if (firstResume[0]) firstResume[0] = false else viewModel.reloadSilently()
        onPauseOrDispose {}
    }

    VehiclesContent(
        state = state,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::refresh,
        onOpenVehicle = onOpenVehicle,
        onOpenSettings = onOpenSettings,
        onOpenWebApp = { state.webAppUrl?.let(openLink) },
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
    onOpenWebApp: () -> Unit,
) {
    Scaffold(
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
                contentPadding = PaddingValues(16.dp),
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
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.profiles_retry)) }
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
                            if (state.webAppUrl != null) {
                                TextButton(onClick = onOpenWebApp) {
                                    Text(stringResource(R.string.profiles_open_web_app))
                                }
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
    val locale = LocalConfiguration.current.locales[0]
    val mileage = NumberFormat.getIntegerInstance(locale).format(summary.vehicle.currentMileage)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(summary.vehicle.name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.vehicle_mileage, mileage),
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
    val colors = LocalStatusColors.current
    val name = when (status) {
        TaskStatus.OVERDUE -> stringResource(R.string.status_overdue)
        TaskStatus.DUE_SOON -> stringResource(R.string.status_due_soon)
        TaskStatus.OK -> stringResource(R.string.status_ok)
        TaskStatus.DONE -> stringResource(R.string.status_done)
        null -> stringResource(R.string.vehicle_no_tasks)
    }
    val color: Color = when (status) {
        TaskStatus.OVERDUE -> colors.overdue
        TaskStatus.DUE_SOON -> colors.dueSoon
        TaskStatus.OK -> colors.ok
        TaskStatus.DONE, null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // "All up to date" is the friendlier wording for OK (the overall status never has due tasks then).
    val text = if (status == TaskStatus.OK || status == TaskStatus.DONE) {
        stringResource(R.string.vehicle_all_up_to_date)
    } else {
        name
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, CircleShape)
                // The status text next to the dot already says it; describe the dot only if that text differs.
                .semantics { if (text != name) contentDescription = name },
        )
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
