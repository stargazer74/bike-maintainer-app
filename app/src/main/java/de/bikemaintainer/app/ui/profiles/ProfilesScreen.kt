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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.ui.common.AppCard
import de.bikemaintainer.app.ui.common.ErrorText
import de.bikemaintainer.app.ui.common.LoadingIndicator
import de.bikemaintainer.app.domain.Profile

/** Stateful entry point: signals [onSignedIn] once after a profile was selected. */
@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel,
    onSignedIn: () -> Unit,
    onOtherServer: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.done) {
        if (state.done) onSignedIn()
    }
    ProfilesContent(
        state = state,
        onSelect = viewModel::select,
        onRetry = viewModel::reload,
        onOtherServer = onOtherServer,
        create = CreateProfileActions(
            onOpen = viewModel::openCreate,
            onNameChange = viewModel::onNewNameChange,
            onCreate = viewModel::create,
        ),
    )
}

data class CreateProfileActions(
    val onOpen: () -> Unit,
    val onNameChange: (String) -> Unit,
    val onCreate: () -> Unit,
)

@Composable
fun ProfilesContent(
    state: ProfilesUiState,
    onSelect: (Long) -> Unit,
    onRetry: () -> Unit,
    onOtherServer: () -> Unit,
    create: CreateProfileActions,
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
                    Text(stringResource(R.string.retry))
                }
            }
            state.profiles.isEmpty() -> {
                Text(text = stringResource(R.string.profiles_empty), style = MaterialTheme.typography.bodyLarge)
                CreateProfile(state, create, first = true)
            }
            else -> {
                state.profiles.forEach { profile -> ProfileCard(state, profile, onSelect) }
                if (state.creatingOpen) {
                    CreateProfile(state, create, first = false)
                } else {
                    OutlinedButton(
                        onClick = create.onOpen,
                        enabled = state.selecting == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.profiles_new))
                    }
                }
            }
        }
        TextButton(onClick = onOtherServer, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.login_other_server))
        }
    }
}

@Composable
private fun ProfileCard(state: ProfilesUiState, profile: Profile, onSelect: (Long) -> Unit) {
    AppCard(
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

/** Name field and "Add"; [first] when there is no person yet (then it is the only way on). */
@Composable
private fun CreateProfile(state: ProfilesUiState, actions: CreateProfileActions, first: Boolean) {
    Text(
        text = stringResource(if (first) R.string.profiles_first else R.string.profiles_new),
        style = MaterialTheme.typography.titleMedium,
    )
    OutlinedTextField(
        value = state.newName,
        onValueChange = actions.onNameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.profiles_name)) },
        singleLine = true,
        enabled = !state.creating,
        isError = state.createError != null,
        supportingText = state.createError?.let { error ->
            {
                Text(
                    stringResource(
                        when (error) {
                            CreateProfileError.NAME_REQUIRED -> R.string.profiles_error_name_required
                            CreateProfileError.NAME_TAKEN -> R.string.profiles_error_name_taken
                            CreateProfileError.FAILED -> R.string.profiles_error_create_failed
                        },
                    ),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { actions.onCreate() }),
    )
    Button(
        onClick = actions.onCreate,
        enabled = !state.creating && state.selecting == null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.creating) LoadingIndicator(small = true, color = MaterialTheme.colorScheme.onPrimary)
            Text(stringResource(R.string.profiles_add))
        }
    }
}
