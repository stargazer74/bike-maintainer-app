package de.bikemaintainer.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.Access
import de.bikemaintainer.app.domain.AccessStatus
import java.time.LocalDate

/*
 * Texts about the access of the account. Deliberately neutral: no price, no link and no hint where or how to buy –
 * the subscription is only sold on the website (Google Play payments policy, Epic backend#136).
 */

/** Why the account is read-only: the trial ended, or a subscription it had. */
@StringRes
fun Access.readOnlyRes(): Int = if (hadSubscription) R.string.access_expired else R.string.access_trial_expired

/** Status line for the settings, `null` if there is nothing to say (subscription, household mode, admins). */
@Composable
fun accessStatusText(access: Access, today: LocalDate): String? = when (access.status) {
    AccessStatus.TRIAL -> access.trialDaysLeft(today)?.let { days ->
        pluralStringResource(R.plurals.access_trial_days_left, days, days)
    } ?: stringResource(R.string.access_trial)
    AccessStatus.EXPIRED -> stringResource(access.readOnlyRes())
    AccessStatus.ACTIVE, AccessStatus.FULL -> null
}

/** Shown instead of the buttons that record something, on a read-only account. */
@Composable
fun ReadOnlyNotice(access: Access, modifier: Modifier = Modifier) {
    Text(
        stringResource(access.readOnlyRes()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
