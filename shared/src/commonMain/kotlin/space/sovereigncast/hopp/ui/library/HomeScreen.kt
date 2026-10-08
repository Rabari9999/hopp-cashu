package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.home_all
import space.sovereigncast.hopp.resources.home_boosted
import space.sovereigncast.hopp.resources.home_recent
import space.sovereigncast.hopp.resources.inbox_dismiss
import space.sovereigncast.hopp.resources.inbox_empty_text
import space.sovereigncast.hopp.resources.inbox_empty_title
import space.sovereigncast.hopp.resources.inbox_no_shows
import space.sovereigncast.hopp.resources.inbox_to_discover
import space.sovereigncast.hopp.resources.library_continue
import space.sovereigncast.hopp.resources.nav_new
import space.sovereigncast.hopp.ui.components.formatSats
import space.sovereigncast.hopp.ui.theme.Hopp

/**
 * Home (device feedback 2026-10-08, replaces the New tab of ADR 0012): the
 * newest of "New" with a way to all of it, continue listening, recently
 * played, recently boosted. Nostr comments join with the identity of phase 2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onPlay: (EpisodeItem) -> Unit, onInbox: () -> Unit, onDiscover: () -> Unit, vm: HomeViewModel = koinViewModel()) {
    val inbox by vm.inbox.collectAsState()
    val hasShows by vm.hasShows.collectAsState()
    val inProgress by vm.inProgress.collectAsState()
    val played by vm.recentlyPlayed.collectAsState()
    val boosted by vm.recentlyBoosted.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val continueTitle = stringResource(Res.string.library_continue)
    val recentTitle = stringResource(Res.string.home_recent)

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh) {
        val newest = inbox ?: return@PullToRefreshBox
        if (hasShows == false) {
            EmptyState(stringResource(Res.string.inbox_empty_title), stringResource(Res.string.inbox_no_shows)) {
                Button(onClick = onDiscover) { Text(stringResource(Res.string.inbox_to_discover)) }
            }
            return@PullToRefreshBox
        }
        if (newest.isEmpty() && inProgress.isEmpty() && played.isEmpty() && boosted.isEmpty()) {
            EmptyState(stringResource(Res.string.inbox_empty_title), stringResource(Res.string.inbox_empty_text))
            return@PullToRefreshBox
        }
        LazyColumn {
            if (newest.isNotEmpty()) {
                item { SectionHeader(stringResource(Res.string.nav_new), stringResource(Res.string.home_all, newest.size), onInbox) }
                items(newest.take(HomeViewModel.HOME_ROWS), key = { "n${it.id}" }) { item ->
                    EpisodeRow(item, showPodcast = true, onClick = { onPlay(item) }) {
                        IconButton(onClick = { vm.dismiss(item) }) {
                            Icon(Icons.Rounded.Close, stringResource(Res.string.inbox_dismiss), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        EpisodeMenu(item)
                    }
                }
            }
            section(continueTitle, inProgress, "c", onPlay)
            section(recentTitle, played, "r", onPlay)
            if (boosted.isNotEmpty()) {
                item { SectionHeader(stringResource(Res.string.home_boosted)) }
                items(boosted, key = { "b${it.first.id}" }) { (item, sats) ->
                    EpisodeRow(item, showPodcast = true, onClick = { onPlay(item) }) {
                        Text("${formatSats(sats)} sats", color = Hopp.colors.valueText, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.section(title: String, list: List<EpisodeItem>, key: String, onPlay: (EpisodeItem) -> Unit) {
    if (list.isEmpty()) return
    item { SectionHeader(title) }
    items(list, key = { key + it.id }) { item -> EpisodeRow(item, showPodcast = true, onClick = { onPlay(item) }) { EpisodeMenu(item) } }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (action != null) Text(
            "$action ›", Modifier.clickable(onClick = onAction).padding(8.dp),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        )
    }
}
