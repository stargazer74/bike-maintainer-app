package de.bikemaintainer.app.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.ui.common.ErrorText
import de.bikemaintainer.app.ui.common.LoadingIndicator
import de.bikemaintainer.app.ui.common.rememberWebLinkOpener

/** Stateful entry point: signals [onSignedIn] once after a profile was selected. */
@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel,
    serverUrl: String,
    onSignedIn: () -> Unit,
    onOtherServer: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.done) {
        if (state.done) onSignedIn()
    }
    // Opens the system browser (no WebView); safe without a browser; the web app routes to its own person selection.
    val openLink = rememberWebLinkOpener()
    ProfilesContent(
        state = state,
        onSelect = viewModel::select,
        onRetry = viewModel::reload,
        onOpenWebApp = { openLink(serverUrl) },
        onOtherServer = onOtherServer,
    )
}

@Composable
fun ProfilesContent(
    state: ProfilesUiState,
    onSelect: (Long) -> Unit,
    onRetry: () -> Unit,
    onOpenWebApp: () -> Unit,
    onOtherServer: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.profiles_title), style = MaterialTheme.typography.headlineLarge)
        Text(text = stringResource(R.string.profiles_subtitle), style = MaterialTheme.typography.bodyLarge)
        state.error?.let { error ->
            ErrorText(
                stringResource(
                    when (error) {
                        ProfilesError.SELECT_FAILED -> R.string.profiles_select_failed
                        ProfilesError.STORAGE -> R.string.error_storage_login
                    },
                ),
            )
        }
        when {
            state.loading -> LoadingIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            state.loadFailed -> {
                ErrorText(stringResource(R.string.profiles_load_failed))
                Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.profiles_retry))
                }
            }
            state.profiles.isEmpty() -> {
                Text(text = stringResource(R.string.profiles_empty), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onOpenWebApp, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.profiles_open_web_app))
                }
            }
            else -> state.profiles.forEach { profile ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = state.selecting == null, role = Role.Button) { onSelect(profile.id) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = profile.displayName, style = MaterialTheme.typography.titleMedium)
                        if (state.selecting == profile.id) LoadingIndicator(small = true)
                    }
                }
            }
        }
        TextButton(onClick = onOtherServer, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.login_other_server))
        }
    }
}
