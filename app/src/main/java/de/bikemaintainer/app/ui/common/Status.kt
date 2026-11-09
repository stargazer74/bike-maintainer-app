package de.bikemaintainer.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.TaskStatus
import de.bikemaintainer.app.ui.theme.LocalStatusColors
import java.text.NumberFormat

/** Name of a status for the status dot and accessibility. */
@StringRes
fun TaskStatus.nameRes(): Int =
    when (this) {
        TaskStatus.OVERDUE -> R.string.status_overdue
        TaskStatus.DUE_SOON -> R.string.status_due_soon
        TaskStatus.OK -> R.string.status_ok
        TaskStatus.DONE -> R.string.status_done
    }

/** Colour of a status; DONE and "no status" (null) use the neutral on-surface variant. */
@Composable
fun TaskStatus?.statusColor(): Color {
    val colors = LocalStatusColors.current
    return when (this) {
        TaskStatus.OVERDUE -> colors.overdue
        TaskStatus.DUE_SOON -> colors.dueSoon
        TaskStatus.OK -> colors.ok
        TaskStatus.DONE, null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** Coloured dot; [description] is the accessibility text (null if the text next to it already says it). */
@Composable
fun StatusDot(color: Color, description: String?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(10.dp)
            .background(color, CircleShape)
            .semantics { if (description != null) contentDescription = description },
    )
}

/** A whole number grouped by the app language ("12.345" / "12,345"). */
@Composable
fun formatNumber(value: Int): String =
    NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(value)

/** "12.345 km". */
@Composable
fun mileageText(km: Int): String = stringResource(R.string.vehicle_mileage, formatNumber(km))
