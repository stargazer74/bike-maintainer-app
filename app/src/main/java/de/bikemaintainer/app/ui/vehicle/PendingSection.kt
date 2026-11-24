package de.bikemaintainer.app.ui.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.PendingItem
import de.bikemaintainer.app.domain.PendingProblem
import de.bikemaintainer.app.domain.PendingState
import de.bikemaintainer.app.ui.common.AppCard
import de.bikemaintainer.app.ui.common.mileageText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What was recorded for this vehicle without a connection and is not on the server yet (app#65): waiting entries
 * are only listed; a possible duplicate asks whether to send it anyway; what cannot be sent says why and can be
 * discarded.
 */
@Composable
fun PendingSection(
    items: List<PendingItem>,
    tasks: List<MaintenanceTask>,
    onSendAnyway: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onEdit: (PendingItem) -> Unit = {},
    /** Off while the vehicle is being deleted: nothing to correct or send for it any more (app#100). */
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.pending_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        items.forEach { PendingCard(it, tasks, onSendAnyway, onDiscard, onEdit, enabled) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingCard(
    item: PendingItem,
    tasks: List<MaintenanceTask>,
    onSendAnyway: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onEdit: (PendingItem) -> Unit,
    enabled: Boolean,
) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val title = when (item.kind) {
                PendingItem.Kind.MILEAGE -> stringResource(R.string.pending_mileage, mileageText(item.mileage))
                PendingItem.Kind.MAINTENANCE ->
                    stringResource(R.string.pending_maintenance, formatDate(item.date), mileageText(item.mileage))
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
            val names = item.taskIds.mapNotNull { id -> tasks.firstOrNull { it.id == id }?.name }
            if (names.isNotEmpty()) Text(names.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
            val state = item.state
            val stateText = when (state) {
                PendingState.Waiting -> stringResource(R.string.pending_waiting)
                is PendingState.PossibleDuplicate -> stringResource(R.string.pending_duplicate, formatDate(state.date))
                is PendingState.Failed -> stringResource(state.problem.messageRes())
            }
            Text(
                stateText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is PendingState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (state != PendingState.Waiting) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.editable) {
                        OutlinedButton(onClick = { onEdit(item) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.pending_edit))
                        }
                    }
                    if (state is PendingState.PossibleDuplicate) {
                        OutlinedButton(onClick = { onSendAnyway(item.id) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.pending_send_anyway))
                        }
                    }
                    TextButton(onClick = { onDiscard(item.id) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.pending_discard))
                    }
                }
            }
        }
    }
}

/**
 * Can be corrected in a form (app#70): a rejected or possibly duplicate maintenance, a mileage below the server's.
 * A deleted vehicle or a read-only account cannot be fixed by editing.
 */
val PendingItem.editable: Boolean
    get() = when (val s = state) {
        PendingState.Waiting -> false
        is PendingState.PossibleDuplicate -> kind == PendingItem.Kind.MAINTENANCE
        is PendingState.Failed -> when (kind) {
            PendingItem.Kind.MAINTENANCE -> s.problem == PendingProblem.REJECTED
            PendingItem.Kind.MILEAGE -> s.problem == PendingProblem.MILEAGE_BELOW_CURRENT
        }
    }

@Composable
private fun formatDate(date: LocalDate?): String {
    if (date == null) return ""
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
}

private fun PendingProblem.messageRes(): Int = when (this) {
    PendingProblem.VEHICLE_GONE -> R.string.pending_problem_vehicle_gone
    PendingProblem.REJECTED -> R.string.pending_problem_rejected
    PendingProblem.MILEAGE_BELOW_CURRENT -> R.string.pending_problem_mileage
    PendingProblem.SUBSCRIPTION_REQUIRED -> R.string.pending_problem_read_only
}
