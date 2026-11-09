package de.bikemaintainer.app.ui.vehicle

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.MaintenanceTask
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.ui.common.formatNumber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What is measured: kilometres or days. */
enum class Axis { KM, DAYS }

/** The status line of a task, independent of Android resources (see [text] for the wording). */
sealed interface TaskLabel {
    enum class Kind { OVERDUE, DUE_SOON, FIRST_DUE, OK }

    /** [value] is always positive (for OVERDUE it is the amount by which the task is overdue). */
    data class Remaining(val kind: Kind, val axis: Axis, val value: Int) : TaskLabel

    data class DoneOn(val date: LocalDate) : TaskLabel

    data object Done : TaskLabel

    data object NeverPerformed : TaskLabel

    data object Ok : TaskLabel
}

fun MaintenanceTask.label(): TaskLabel {
    if (status == TaskStatus.DONE) return lastServiceDate?.let { TaskLabel.DoneOn(it) } ?: TaskLabel.Done
    val neverPerformed = lastServiceMileage == null && lastServiceDate == null
    // The server sets at most one of the two; kilometres win.
    val (axis, remaining) = when {
        kmRemaining != null -> Axis.KM to kmRemaining
        daysRemaining != null -> Axis.DAYS to daysRemaining
        else -> return if (neverPerformed) TaskLabel.NeverPerformed else TaskLabel.Ok
    }
    val kind = when {
        status == TaskStatus.OVERDUE -> TaskLabel.Kind.OVERDUE
        status == TaskStatus.DUE_SOON -> TaskLabel.Kind.DUE_SOON
        neverPerformed -> TaskLabel.Kind.FIRST_DUE
        else -> TaskLabel.Kind.OK
    }
    return TaskLabel.Remaining(kind, axis, if (kind == TaskLabel.Kind.OVERDUE) -remaining else remaining)
}

@Composable
fun TaskLabel.text(): String =
    when (this) {
        is TaskLabel.Remaining -> {
            val number = formatNumber(value)
            if (axis == Axis.KM) {
                stringResource(
                    when (kind) {
                        TaskLabel.Kind.OVERDUE -> R.string.task_overdue_km
                        TaskLabel.Kind.DUE_SOON -> R.string.task_due_soon_km
                        TaskLabel.Kind.FIRST_DUE -> R.string.task_first_due_km
                        TaskLabel.Kind.OK -> R.string.task_ok_km
                    },
                    number,
                )
            } else {
                pluralStringResource(
                    when (kind) {
                        TaskLabel.Kind.OVERDUE -> R.plurals.task_overdue_days
                        TaskLabel.Kind.DUE_SOON -> R.plurals.task_due_soon_days
                        TaskLabel.Kind.FIRST_DUE -> R.plurals.task_first_due_days
                        TaskLabel.Kind.OK -> R.plurals.task_ok_days
                    },
                    value,
                    number,
                )
            }
        }
        is TaskLabel.DoneOn -> {
            val locale = LocalConfiguration.current.locales[0]
            stringResource(
                R.string.task_done_on,
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date),
            )
        }
        TaskLabel.Done -> stringResource(R.string.status_done)
        TaskLabel.NeverPerformed -> stringResource(R.string.task_never_performed)
        TaskLabel.Ok -> stringResource(R.string.status_ok)
    }
