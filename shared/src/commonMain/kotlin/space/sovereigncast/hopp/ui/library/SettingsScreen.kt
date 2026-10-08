package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.opml_export
import space.sovereigncast.hopp.resources.settings_about
import space.sovereigncast.hopp.resources.settings_version
import space.sovereigncast.hopp.ui.LocalPlatformActions

/** Package 1 placeholder: export and about. Wallet, theme and privacy
 *  (ADR 0015) arrive in packages 4 and 5. */
@Composable
fun SettingsScreen(vm: DiscoverViewModel = koinViewModel()) {
    val app: AppInfo = koinInject()
    val actions = LocalPlatformActions.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { scope.launch { actions.exportOpml(vm.exportOpml()) } }) { Text(stringResource(Res.string.opml_export)) }
        Text(stringResource(Res.string.settings_about), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(Res.string.settings_version, app.version), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
    }
}
