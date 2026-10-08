package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.inbox_dismiss
import space.sovereigncast.hopp.resources.inbox_empty_text
import space.sovereigncast.hopp.resources.inbox_empty_title
import space.sovereigncast.hopp.resources.inbox_no_shows
import space.sovereigncast.hopp.resources.inbox_to_discover

/** "New" (ADR 0012): what arrived since the listener last looked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(onPlay: (EpisodeItem) -> Unit, onDiscover: () -> Unit, vm: InboxViewModel = koinViewModel()) {
    val inbox by vm.inbox.collectAsState()
    val hasShows by vm.hasShows.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh) {
        val list = inbox ?: return@PullToRefreshBox
        when {
            hasShows == false -> EmptyState(stringResource(Res.string.inbox_empty_title), stringResource(Res.string.inbox_no_shows)) {
                Button(onClick = onDiscover) { Text(stringResource(Res.string.inbox_to_discover)) }
            }
            list.isEmpty() -> EmptyState(stringResource(Res.string.inbox_empty_title), stringResource(Res.string.inbox_empty_text))
            else -> LazyColumn {
                items(list, key = { it.id }) { item ->
                    EpisodeRow(item, showPodcast = true, onClick = { onPlay(item) }) {
                        IconButton(onClick = { vm.dismiss(item) }) {
                            Icon(Icons.Rounded.Close, stringResource(Res.string.inbox_dismiss), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        EpisodeMenu(item)
                    }
                }
            }
        }
    }
}
