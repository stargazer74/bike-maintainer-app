package de.bikemaintainer.app.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R

/** A spinner that screen readers announce as "Loading…"; [small] for use inside a button or list row. */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    small: Boolean = false,
    color: Color = ProgressIndicatorDefaults.circularColor,
) {
    val description = stringResource(R.string.loading)
    CircularProgressIndicator(
        modifier = modifier
            .then(if (small) Modifier.size(20.dp) else Modifier)
            .semantics { contentDescription = description },
        color = color,
        strokeWidth = if (small) 2.dp else ProgressIndicatorDefaults.CircularStrokeWidth,
    )
}

/** An error message that screen readers announce as soon as it appears (polite live region). */
@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
