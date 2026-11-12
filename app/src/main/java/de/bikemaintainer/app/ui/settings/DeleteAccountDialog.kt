package de.bikemaintainer.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.ui.common.ErrorText
import de.bikemaintainer.app.ui.common.LoadingIndicator

private enum class DeleteStep { PASSWORD, CONFIRM }

/**
 * Two steps: first what gets lost plus the password, then a second, explicit "really delete?" – only that one calls
 * [onDelete]. The password lives only in this dialog's memory (`remember`, not saved state) and is gone with it.
 * A failure brings the dialog back to the first step with its message; a wrong password is cleared.
 */
@Composable
fun DeleteAccountDialog(
    email: String?,
    deleting: Boolean,
    error: DeleteAccountError?,
    onDelete: (String) -> Unit,
    onPasswordChange: () -> Unit,
    onDismiss: () -> Unit,
) {
    var step by remember { mutableStateOf(DeleteStep.PASSWORD) }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(error) {
        if (error != null) {
            step = DeleteStep.PASSWORD
            if (error == DeleteAccountError.WRONG_PASSWORD) password = ""
        }
    }
    val dismiss = { if (!deleting) onDismiss() }
    when (step) {
        DeleteStep.PASSWORD -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.settings_delete_account)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_delete_account_text))
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            onPasswordChange()
                        },
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                        label = { Text(stringResource(R.string.login_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    error?.let { ErrorText(stringResource(it.messageRes())) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { step = DeleteStep.CONFIRM },
                    enabled = password.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.settings_delete_account_next)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
        DeleteStep.CONFIRM -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.settings_delete_account_confirm_title)) },
            text = {
                Text(
                    if (email != null) stringResource(R.string.settings_delete_account_confirm_text, email)
                    else stringResource(R.string.settings_delete_account_confirm_text_unnamed),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(password) },
                    enabled = !deleting,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (deleting) LoadingIndicator(small = true, color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.settings_delete_account_final))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { step = DeleteStep.PASSWORD },
                    enabled = !deleting,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.back)) }
            },
        )
    }
}

private fun DeleteAccountError.messageRes(): Int = when (this) {
    DeleteAccountError.WRONG_PASSWORD -> R.string.delete_account_error_wrong_password
    DeleteAccountError.LAST_ADMIN -> R.string.delete_account_error_last_admin
    DeleteAccountError.TOO_MANY_ATTEMPTS -> R.string.register_error_too_many
    DeleteAccountError.NOT_REACHABLE -> R.string.error_server_not_reachable
    DeleteAccountError.UNKNOWN -> R.string.delete_account_error_unknown
}
