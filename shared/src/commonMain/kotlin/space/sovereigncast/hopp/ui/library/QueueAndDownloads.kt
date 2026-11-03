package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.data.DownloadState
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.downloads_empty
import space.sovereigncast.hopp.resources.downloads_empty_text
import space.sovereigncast.hopp.resources.downloads_used
import space.sovereigncast.hopp.resources.downloads_used_unlimited
import space.sovereigncast.hopp.resources.menu_cancel_download
import space.sovereigncast.hopp.resources.menu_remove_download
import space.sovereigncast.hopp.resources.queue_empty
import space.sovereigncast.hopp.resources.queue_empty_text
import space.sovereigncast.hopp.ui.components.formatBytes
import space.sovereigncast.hopp.resources.downloads_start_now
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.pluralStringResource
import space.sovereigncast.hopp.resources.cancel
import space.sovereigncast.hopp.resources.playlist_clear
import space.sovereigncast.hopp.resources.playlist_clear_confirm
import space.sovereigncast.hopp.resources.show_episodes

/** The playlist as its own page (Dennis, maintainer 2026-11-03): how many and how long, "Now", what
 *  comes next to drag and take out, and emptying it, asked first. */
@Composable
fun QueueScreen(vm: QueueViewModel = koinViewModel()) {
    val nav = LocalEpisodeNav.current
    val items by vm.items.collectAsState()
    val now = playlistNow()
    val list = items?.filter { it.id != now?.id } ?: return
    if (list.isEmpty() && now == null) {
        EmptyState(stringResource(Res.string.queue_empty), stringResource(Res.string.queue_empty_text))
        return
    }
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmClear = false },
        text = { Text(stringResource(Res.string.playlist_clear_confirm, list.size)) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmClear = false; vm.clear() }) { Text(stringResource(Res.string.playlist_clear)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmClear = false }) { Text(stringResource(Res.string.cancel)) } },
    )
    val count = list.size + (if (now != null) 1 else 0)
    val left = playlistLeftMs(now, null, list)
    QueueList(
        list, onPlay = nav.open, onRemove = vm::remove, onReorder = vm::reorder,
        header = {
            item(key = "summary") {
                Text(
                    listOfNotNull(pluralStringResource(Res.plurals.show_episodes, count, count), left?.let { listenDuration(it) }).joinToString(" · "),
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            now?.let { n -> item(key = "now") { PlaylistNowCard(n, nav.open) } }
            if (now != null && list.isNotEmpty()) item(key = "after") { PlaylistAfterLabel() }
        },
        footer = {
            if (list.isNotEmpty()) item(key = "clear") {
                androidx.compose.material3.TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                    Text(stringResource(Res.string.playlist_clear), color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

/** Everything downloaded or on its way, with the space it takes. */
@Composable
fun DownloadsScreen(vm: DownloadsViewModel = koinViewModel(), settings: SettingsViewModel = koinViewModel()) {
    val nav = LocalEpisodeNav.current
    val items by vm.items.collectAsState()
    val used by vm.usedBytes.collectAsState()
    val waiting by vm.waiting.collectAsState()
    val limitMb = settings.current.collectAsState().value?.storageLimitMb
    val list = items ?: return
    if (list.isEmpty()) {
        EmptyState(stringResource(Res.string.downloads_empty), stringResource(Res.string.downloads_empty_text))
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            if (limitMb == null || limitMb == 0) stringResource(Res.string.downloads_used_unlimited, formatBytes(used))
            else stringResource(Res.string.downloads_used, formatBytes(used), formatBytes(limitMb * 1024L * 1024L)),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // Automatic downloads wait for Wi-Fi; on mobile data the listener may not want to.
        if (waiting > 0) TextButton(onClick = vm::startAllNow, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(stringResource(Res.string.downloads_start_now, waiting.toInt()))
        }
        LazyColumn {
            items(list, key = { it.id }) { item ->
                EpisodeRow(item, showPodcast = true, onClick = { nav.open(item) }) {
                    PlayButton(item)
                    IconButton(onClick = { vm.remove(item) }) {
                        Icon(
                            Icons.Rounded.Delete,
                            stringResource(if (item.downloadState == DownloadState.DONE) Res.string.menu_remove_download else Res.string.menu_cancel_download),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
