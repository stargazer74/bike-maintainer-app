package de.bikemaintainer.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R

/** Stateful entry point: hands the navigation target over once and then clears it. */
@Composable
fun SetupScreen(viewModel: SetupViewModel, onNavigate: (Any) -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.next) {
        state.next?.let {
            onNavigate(it)
            viewModel.onNavigated()
        }
    }
    SetupContent(
        state = state,
        onAddressChange = viewModel::onAddressChange,
        onContinue = viewModel::onContinue,
        onCleartextConfirmed = viewModel::onCleartextConfirmed,
        onCleartextDismissed = viewModel::onCleartextDismissed,
    )
}

@Composable
fun SetupContent(
    state: SetupUiState,
    onAddressChange: (String) -> Unit,
    onContinue: () -> Unit,
    onCleartextConfirmed: () -> Unit,
    onCleartextDismissed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        OutlinedTextField(
            value = state.address,
            onValueChange = onAddressChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.setup_server_address)) },
            singleLine = true,
            isError = state.error != null,
            supportingText = state.error?.let { { Text(stringResource(it.messageRes())) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onContinue() }),
        )
        Button(
            onClick = onContinue,
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.setup_continue))
            }
        }
    }
    if (state.confirmCleartext) {
        AlertDialog(
            onDismissRequest = onCleartextDismissed,
            text = { Text(stringResource(R.string.setup_cleartext_hint)) },
            confirmButton = {
                TextButton(onClick = onCleartextConfirmed) { Text(stringResource(R.string.setup_connect_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = onCleartextDismissed) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private fun SetupError.messageRes(): Int = when (this) {
    SetupError.INVALID_ADDRESS -> R.string.setup_error_invalid_address
    SetupError.NOT_REACHABLE -> R.string.error_server_not_reachable
    SetupError.NOT_BIKE_MAINTAINER -> R.string.setup_error_not_bike_maintainer
    SetupError.CLEARTEXT_NOT_ALLOWED -> R.string.error_cleartext_not_allowed
}
