package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.data.SubscribeResult
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.discover_add_button
import space.sovereigncast.hopp.resources.discover_add_hint
import space.sovereigncast.hopp.resources.discover_add_title
import space.sovereigncast.hopp.resources.discover_search_soon
import space.sovereigncast.hopp.resources.open_show
import space.sovereigncast.hopp.resources.opml_import
import space.sovereigncast.hopp.resources.opml_section
import space.sovereigncast.hopp.resources.opml_text
import space.sovereigncast.hopp.resources.result_already
import space.sovereigncast.hopp.resources.result_failed
import space.sovereigncast.hopp.resources.result_imported
import space.sovereigncast.hopp.resources.result_subscribed
import space.sovereigncast.hopp.ui.LocalPlatformActions

/** "Discover" in package 1: subscribe by feed address, import OPML. The
 *  Podcast Index search (ADR 0008) arrives in package 3. */
@Composable
fun DiscoverScreen(onShow: (Long) -> Unit, vm: DiscoverViewModel = koinViewModel()) {
    val state by vm.state.collectAsState()
    var url by remember { mutableStateOf("") }
    val actions = LocalPlatformActions.current
    val colors = MaterialTheme.colorScheme
    // "Subscribed to …" is about the moment it happened; coming back to the
    // tab later it would point at a show that may be gone.
    DisposableEffect(vm) { onDispose { vm.reset() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.discover_search_soon), color = colors.onSurfaceVariant)
        Text(stringResource(Res.string.discover_add_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = url, onValueChange = { url = it; vm.reset() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.discover_add_hint)) }, placeholder = { Text("https://…") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (url.isNotBlank()) vm.subscribe(url) }),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.subscribe(url) }, enabled = url.isNotBlank() && state != AddState.Working) {
                Text(stringResource(Res.string.discover_add_button))
            }
            if (state == AddState.Working) CircularProgressIndicator(Modifier.padding(4.dp))
        }
        when (val s = state) {
            is AddState.Done -> when (val r = s.result) {
                is SubscribeResult.Subscribed -> ResultLine(stringResource(Res.string.result_subscribed, r.title)) { onShow(r.podcastId) }
                is SubscribeResult.AlreadySubscribed -> ResultLine(stringResource(Res.string.result_already)) { onShow(r.podcastId) }
                is SubscribeResult.Failed -> Text(stringResource(Res.string.result_failed, r.reason), color = colors.error)
            }
            is AddState.Imported -> Text(stringResource(Res.string.result_imported, s.subscribed, s.already, s.failed))
            else -> Unit
        }

        Text(stringResource(Res.string.opml_section), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(stringResource(Res.string.opml_text), color = colors.onSurfaceVariant)
        OutlinedButton(onClick = { actions.pickOpml { vm.importOpml(it) } }, enabled = state != AddState.Working) {
            Text(stringResource(Res.string.opml_import))
        }
    }
}

@Composable
private fun ResultLine(text: String, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        TextButton(onClick = onOpen) { Text(stringResource(Res.string.open_show)) }
    }
}
