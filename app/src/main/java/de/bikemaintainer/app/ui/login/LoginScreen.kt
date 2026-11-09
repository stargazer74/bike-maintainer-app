package de.bikemaintainer.app.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.ui.common.WebLinks

/** Stateful entry point: signals [onSignedIn] once after a successful login. */
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    serverUrl: String,
    onSignedIn: () -> Unit,
    onOtherServer: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.done) {
        if (state.done) onSignedIn()
    }
    // Opens the system browser (no WebView).
    val uriHandler = LocalUriHandler.current
    LoginContent(
        state = state,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onDeviceNameChange = viewModel::onDeviceNameChange,
        onSubmit = viewModel::onSubmit,
        onRegister = { uriHandler.openUri(WebLinks.register(serverUrl)) },
        onForgotPassword = { uriHandler.openUri(WebLinks.forgotPassword(serverUrl)) },
        onOtherServer = onOtherServer,
    )
}

@Composable
fun LoginContent(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRegister: () -> Unit,
    onForgotPassword: () -> Unit,
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
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        if (state.reloginHint) {
            Text(text = stringResource(R.string.login_relogin_hint), style = MaterialTheme.typography.bodyLarge)
        }
        OutlinedTextField(
            value = state.email,
            onValueChange = onEmailChange,
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
            label = { Text(stringResource(R.string.login_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
            label = { Text(stringResource(R.string.login_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = state.deviceName,
            onValueChange = onDeviceNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.login_device_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        )
        state.error?.let {
            Text(
                text = stringResource(it.messageRes()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = onSubmit,
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Text(stringResource(R.string.login_submit))
            }
        }
        TextButton(onClick = onRegister, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.login_register))
        }
        TextButton(onClick = onForgotPassword, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.login_forgot_password))
        }
        TextButton(onClick = onOtherServer, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.login_other_server))
        }
    }
}

private fun LoginError.messageRes(): Int = when (this) {
    LoginError.REQUIRED -> R.string.login_error_required
    LoginError.INVALID_CREDENTIALS -> R.string.login_error_invalid_credentials
    LoginError.ACCOUNT_DISABLED -> R.string.login_error_account_disabled
    LoginError.EMAIL_NOT_VERIFIED -> R.string.login_error_email_not_verified
    LoginError.TOO_MANY_ATTEMPTS -> R.string.login_error_too_many_attempts
    LoginError.NOT_REACHABLE -> R.string.error_server_not_reachable
    LoginError.CLEARTEXT_NOT_ALLOWED -> R.string.error_cleartext_not_allowed
    LoginError.UNKNOWN -> R.string.login_error_unknown
}
