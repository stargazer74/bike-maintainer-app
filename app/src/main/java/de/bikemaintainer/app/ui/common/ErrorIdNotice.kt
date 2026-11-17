package de.bikemaintainer.app.ui.common

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R

/**
 * Error ID of the last server error above all screens, until closed: the screen shows its own error text, this adds
 * the ID to quote in a report. Nothing while [errorId] is null. Announced politely, like [ErrorText].
 */
@Composable
fun ErrorIdNotice(errorId: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (errorId == null) return
    Snackbar(
        modifier = modifier
            .navigationBarsPadding()
            .padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        action = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.error_id_dismiss))
            }
        },
    ) {
        Text(stringResource(R.string.error_id_notice, errorId))
    }
}
