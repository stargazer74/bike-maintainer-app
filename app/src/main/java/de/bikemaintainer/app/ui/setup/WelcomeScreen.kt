package de.bikemaintainer.app.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.ui.common.AppCard

/**
 * First start: online account (the portal, address built in) or own server. Neutral on purpose – no prices, no
 * comparison (Play payment policy); an account is created right in the app, a subscription only ever on the web.
 */
@Composable
fun WelcomeScreen(onOnlineAccount: () -> Unit, onOwnServer: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(text = stringResource(R.string.welcome_title), style = MaterialTheme.typography.titleLarge)
        Choice(R.string.welcome_online, R.string.welcome_online_text, onOnlineAccount)
        Choice(R.string.welcome_own_server, R.string.welcome_own_server_text, onOwnServer)
    }
}

@Composable
private fun Choice(title: Int, text: Int, onClick: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
