package de.bikemaintainer.app.ui.register

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.ui.common.ErrorText
import de.bikemaintainer.app.ui.common.LoadingIndicator
import de.bikemaintainer.app.ui.common.WebLinks
import de.bikemaintainer.app.ui.common.rememberWebLinkOpener

/** Stateful entry point; [onToLogin] leads back to the login of the same server. */
@Composable
fun RegisterScreen(viewModel: RegisterViewModel, onToLogin: () -> Unit) {
    val state by viewModel.state.collectAsState()
    // The configuration locale follows the app language (settings), so the account gets the language the user sees.
    val german = LocalConfiguration.current.locales[0]?.language == "de"
    val openLink = rememberWebLinkOpener()
    RegisterContent(
        state = state,
        actions = RegisterActions(
            onEmailChange = viewModel::onEmailChange,
            onPasswordChange = viewModel::onPasswordChange,
            onPasswordRepeatChange = viewModel::onPasswordRepeatChange,
            onSubmit = { viewModel.onSubmit(german) },
            onResend = viewModel::onResend,
            onToLogin = onToLogin,
            onPrivacy = { site -> openLink(WebLinks.privacy(site, german)) },
        ),
    )
}

data class RegisterActions(
    val onEmailChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onPasswordRepeatChange: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onResend: () -> Unit,
    val onToLogin: () -> Unit,
    /** Opens the privacy policy of the given website. */
    val onPrivacy: (String) -> Unit,
)

@Composable
fun RegisterContent(state: RegisterUiState, actions: RegisterActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val sentTo = state.sentTo
        if (sentTo == null) Form(state, actions) else Sent(sentTo, state, actions)
        state.websiteUrl?.let { site ->
            TextButton(onClick = { actions.onPrivacy(site) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.register_privacy))
            }
        }
    }
}

@Composable
private fun Form(state: RegisterUiState, actions: RegisterActions) {
    Text(text = stringResource(R.string.register_title), style = MaterialTheme.typography.headlineLarge)
    OutlinedTextField(
        value = state.email,
        onValueChange = actions.onEmailChange,
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
        label = { Text(stringResource(R.string.login_email)) },
        singleLine = true,
        enabled = !state.working,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        value = state.password,
        onValueChange = actions.onPasswordChange,
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.NewPassword },
        label = { Text(stringResource(R.string.login_password)) },
        supportingText = { Text(stringResource(R.string.register_password_rules)) },
        singleLine = true,
        enabled = !state.working,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        value = state.passwordRepeat,
        onValueChange = actions.onPasswordRepeatChange,
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.NewPassword },
        label = { Text(stringResource(R.string.register_password_repeat)) },
        singleLine = true,
        enabled = !state.working,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { actions.onSubmit() }),
    )
    state.error?.let { ErrorText(stringResource(it.messageRes())) }
    Button(
        onClick = actions.onSubmit,
        enabled = !state.working,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.working) LoadingIndicator(small = true, color = MaterialTheme.colorScheme.onPrimary)
            Text(stringResource(R.string.register_submit))
        }
    }
    if (state.working) {
        Text(
            stringResource(R.string.register_working),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    TextButton(onClick = actions.onToLogin, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.register_to_login))
    }
}

@Composable
private fun Sent(email: String, state: RegisterUiState, actions: RegisterActions) {
    Text(
        text = stringResource(R.string.register_sent_title),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Text(text = stringResource(R.string.register_sent_text, email), style = MaterialTheme.typography.bodyLarge)
    state.error?.let { ErrorText(stringResource(it.messageRes())) }
    if (state.resent) {
        Text(
            stringResource(R.string.register_resent),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Button(onClick = actions.onToLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.register_to_login))
    }
    OutlinedButton(
        onClick = actions.onResend,
        enabled = !state.resending,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.resending) LoadingIndicator(small = true)
            Text(stringResource(R.string.register_resend))
        }
    }
}

private fun RegisterError.messageRes(): Int = when (this) {
    RegisterError.EMAIL_REQUIRED -> R.string.register_error_email_required
    RegisterError.PASSWORD_LENGTH -> R.string.register_error_password_length
    RegisterError.PASSWORD_TOO_LONG -> R.string.register_error_password_too_long
    RegisterError.PASSWORD_MISMATCH -> R.string.register_error_password_mismatch
    RegisterError.INVALID -> R.string.register_error_invalid
    RegisterError.CAPTCHA -> R.string.register_error_captcha
    RegisterError.MAIL_QUOTA -> R.string.register_error_mail_quota
    RegisterError.TOO_MANY_ATTEMPTS -> R.string.register_error_too_many
    RegisterError.NOT_REACHABLE -> R.string.error_server_not_reachable
    RegisterError.CLEARTEXT_NOT_ALLOWED -> R.string.error_cleartext_not_allowed
    RegisterError.UNKNOWN -> R.string.register_error_unknown
}
