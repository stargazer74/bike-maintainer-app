package de.bikemaintainer.app.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import de.bikemaintainer.app.R
import de.bikemaintainer.app.ui.common.WebLinks
import de.bikemaintainer.app.ui.common.rememberWebLinkOpener

private enum class Confirm { LOGOUT, SWITCH_SERVER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onNavigate: (Any) -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.next) {
        state.next?.let {
            viewModel.navigated()
            onNavigate(it)
        }
    }
    var confirm by rememberSaveable { mutableStateOf<Confirm?>(null) }
    val openLink = rememberWebLinkOpener()
    // The configuration locale follows setApplicationLocales, so this reacts to the picker below.
    val german = LocalConfiguration.current.locales[0]?.language == "de"
    val selected = AppCompatDelegate.getApplicationLocales().let { if (it.isEmpty) "" else it[0]?.language.orEmpty() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section(R.string.settings_language)
            LanguageOption(R.string.settings_language_system, selected == "") { setLanguage("") }
            LanguageOption(R.string.settings_language_german, selected == "de") { setLanguage("de") }
            LanguageOption(R.string.settings_language_english, selected == "en") { setLanguage("en") }

            Section(R.string.settings_connection)
            state.serverUrl?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            val who = state.account?.let { it.email ?: it.displayName }
            who?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            OutlinedButton(
                onClick = { confirm = Confirm.LOGOUT },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.settings_logout)) }
            OutlinedButton(
                onClick = { confirm = Confirm.SWITCH_SERVER },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.settings_switch_server)) }

            state.websiteUrl?.let { site ->
                Section(R.string.settings_legal)
                LinkButton(R.string.legal_imprint) { openLink(WebLinks.imprint(site, german)) }
                LinkButton(R.string.legal_privacy) { openLink(WebLinks.privacy(site, german)) }
                LinkButton(R.string.legal_website) { openLink(WebLinks.home(site, german)) }
            }
        }
    }

    confirm?.let { which ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    stringResource(
                        if (which == Confirm.LOGOUT) R.string.settings_logout else R.string.settings_switch_server,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (which == Confirm.LOGOUT) R.string.settings_logout_confirm
                        else R.string.settings_switch_server_confirm,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        if (which == Confirm.LOGOUT) viewModel.logout() else viewModel.switchServer()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(
                            if (which == Confirm.LOGOUT) R.string.settings_logout else R.string.settings_switch_server,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun setLanguage(tag: String) =
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun LanguageOption(label: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(label), modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun LinkButton(label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(label)) }
}
