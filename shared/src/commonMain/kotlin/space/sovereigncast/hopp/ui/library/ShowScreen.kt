package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.fillMaxWidth
import space.sovereigncast.hopp.resources.show_auto_download
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.cancel
import space.sovereigncast.hopp.resources.mark_played
import space.sovereigncast.hopp.resources.mark_unplayed
import space.sovereigncast.hopp.resources.show_episodes
import space.sovereigncast.hopp.resources.show_feed_error
import space.sovereigncast.hopp.resources.show_subscribed
import space.sovereigncast.hopp.resources.show_unsubscribe
import space.sovereigncast.hopp.resources.show_unsubscribe_confirm

/** A subscribed show: header, then its episodes. The Sovereigncast card and
 *  the Community tab (ADR 0019) arrive in package 5. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowScreen(podcastId: Long, onGone: () -> Unit) {
    val nav = LocalEpisodeNav.current
    val vm: ShowViewModel = koinViewModel(key = "show-$podcastId") { parametersOf(podcastId) }
    val show by vm.show.collectAsState()
    val episodes by vm.episodeList.collectAsState()
    val count by vm.episodeCount.collectAsState()
    val listState = rememberLazyListState()
    OnNearEnd(listState) { vm.loadMore() }
    val meta by vm.meta.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val gone by vm.gone.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(gone) { if (gone) onGone() }
    val colors = MaterialTheme.colorScheme

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh) {
        LazyColumn(state = listState) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(show?.imageUrl, 112.dp, corner = 18.dp)
                    Spacer(Modifier.size(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(show?.title ?: "", style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        show?.author?.let { Text(it, color = colors.onSurfaceVariant, maxLines = 1) }
                        episodes?.let { Text(stringResource(Res.string.show_episodes, count.toInt()), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                        OutlinedButton(onClick = { confirm = true }) { Text("✓ " + stringResource(Res.string.show_subscribed)) }
                    }
                }
                show?.let { s ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(Res.string.show_auto_download), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = s.autoDownload, onCheckedChange = vm::setAutoDownload)
                    }
                }
                show?.lastError?.let {
                    Text(stringResource(Res.string.show_feed_error, it), color = colors.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp))
                }
                meta?.description?.let { d ->
                    Text(plainText(d), color = colors.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            items(episodes.orEmpty(), key = { it.id }) { item ->
                EpisodeRow(item, showPodcast = false, onClick = { nav.open(item) }) {
                    PlayButton(item)
                    EpisodeMenu(item)
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text(stringResource(Res.string.show_unsubscribe_confirm, show?.title ?: "")) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.unsubscribe() }) { Text(stringResource(Res.string.show_unsubscribe)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

/** Show notes come as HTML; the header only needs readable text. */
internal fun plainText(html: String): String =
    html.replace(Regex("<br\\s*/?>|</p>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("\n{3,}"), "\n\n").trim()
