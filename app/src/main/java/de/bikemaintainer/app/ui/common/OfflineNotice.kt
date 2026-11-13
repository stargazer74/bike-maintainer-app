package de.bikemaintainer.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import de.bikemaintainer.app.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Shown instead of "No connection." while a screen shows the data stored offline: from when it is. Not an error
 * colour – the screen works, the data is only possibly out of date. Announced politely, like [ErrorText].
 */
@Composable
fun OfflineNotice(asOf: Instant, modifier: Modifier = Modifier, zone: ZoneId = ZoneId.systemDefault()) {
    val formatted = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(zone).format(asOf)
    Text(
        text = stringResource(R.string.offline_as_of, formatted),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
