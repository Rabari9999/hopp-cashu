package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import space.sovereigncast.hopp.resources.settings_apple
import space.sovereigncast.hopp.resources.settings_directories
import space.sovereigncast.hopp.resources.settings_fyyd
import space.sovereigncast.hopp.resources.settings_key
import space.sovereigncast.hopp.resources.settings_own_key
import space.sovereigncast.hopp.resources.settings_own_key_note
import space.sovereigncast.hopp.resources.settings_pi_note
import space.sovereigncast.hopp.resources.settings_save
import space.sovereigncast.hopp.resources.settings_secret
import space.sovereigncast.hopp.resources.settings_sees_searches
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.opml_export
import space.sovereigncast.hopp.resources.settings_about
import space.sovereigncast.hopp.resources.settings_downloads
import space.sovereigncast.hopp.resources.settings_downloads_text
import space.sovereigncast.hopp.resources.settings_playback
import space.sovereigncast.hopp.resources.settings_seconds
import space.sovereigncast.hopp.resources.settings_skip_back
import space.sovereigncast.hopp.resources.settings_skip_forward
import space.sovereigncast.hopp.resources.settings_storage
import space.sovereigncast.hopp.resources.settings_subscriptions
import space.sovereigncast.hopp.resources.settings_unlimited
import space.sovereigncast.hopp.resources.settings_version
import space.sovereigncast.hopp.resources.skip_silence
import space.sovereigncast.hopp.ui.LocalPlatformActions
import space.sovereigncast.hopp.ui.components.formatBytes

/** Playback and downloads (ADR 0012), subscriptions, about. Wallet, theme
 *  and privacy (ADR 0015) arrive in packages 4 and 5. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = koinViewModel()) {
    val app: AppInfo = koinInject()
    val engine: PlaybackEngine = koinInject()
    val settings by vm.current.collectAsState()
    val actions = LocalPlatformActions.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val s = settings ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section(stringResource(Res.string.settings_playback))
        Label(stringResource(Res.string.settings_skip_back))
        Choices(Settings.skipBackChoices, s.skipBackSeconds, { stringResource(Res.string.settings_seconds, it) }) { v -> vm.update { it.copy(skipBackSeconds = v) } }
        Label(stringResource(Res.string.settings_skip_forward))
        Choices(Settings.skipForwardChoices, s.skipForwardSeconds, { stringResource(Res.string.settings_seconds, it) }) { v -> vm.update { it.copy(skipForwardSeconds = v) } }
        if (engine.supportsSkipSilence) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.skip_silence), Modifier.weight(1f))
                Switch(checked = s.skipSilence, onCheckedChange = { on -> vm.update { it.copy(skipSilence = on) } })
            }
        }

        Section(stringResource(Res.string.settings_downloads))
        Label(stringResource(Res.string.settings_storage))
        Choices(Settings.storageChoicesMb, s.storageLimitMb, { mb ->
            if (mb == 0) stringResource(Res.string.settings_unlimited) else formatBytes(mb * 1024L * 1024L)
        }) { v -> vm.update { it.copy(storageLimitMb = v) } }
        Text(stringResource(Res.string.settings_downloads_text), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

        Section(stringResource(Res.string.settings_directories))
        Text(stringResource(Res.string.settings_pi_note), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Toggle(stringResource(Res.string.settings_fyyd), stringResource(Res.string.settings_sees_searches), s.fyydSearch) { on -> vm.update { it.copy(fyydSearch = on) } }
        Toggle(stringResource(Res.string.settings_apple), stringResource(Res.string.settings_sees_searches), s.appleSearch) { on -> vm.update { it.copy(appleSearch = on) } }
        Label(stringResource(Res.string.settings_own_key))
        var key by remember(s.podcastIndexKey) { mutableStateOf(s.podcastIndexKey) }
        var secret by remember(s.podcastIndexSecret) { mutableStateOf(s.podcastIndexSecret) }
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(Res.string.settings_key)) })
        OutlinedTextField(
            secret, { secret = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(Res.string.settings_secret)) },
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.settings_own_key_note), Modifier.weight(1f), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { vm.update { it.copy(podcastIndexKey = key.trim(), podcastIndexSecret = secret.trim()) } },
                enabled = key != s.podcastIndexKey || secret != s.podcastIndexSecret,
            ) { Text(stringResource(Res.string.settings_save)) }
        }

        Section(stringResource(Res.string.settings_subscriptions))
        OutlinedButton(onClick = { scope.launch { actions.exportOpml(vm.exportOpml()) } }) { Text(stringResource(Res.string.opml_export)) }

        Text(stringResource(Res.string.settings_about), color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
        Text(stringResource(Res.string.settings_version, app.version), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Section(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun Toggle(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
