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

/** "Up next" as its own page, reached from the Library. */
@Composable
fun QueueScreen(vm: QueueViewModel = koinViewModel()) {
    val nav = LocalEpisodeNav.current
    val items by vm.items.collectAsState()
    val list = items ?: return
    if (list.isEmpty()) {
        EmptyState(stringResource(Res.string.queue_empty), stringResource(Res.string.queue_empty_text))
        return
    }
    QueueList(list, onPlay = nav.open, onRemove = vm::remove, onReorder = vm::reorder)
}

/** Everything downloaded or on its way, with the space it takes. */
@Composable
fun DownloadsScreen(vm: DownloadsViewModel = koinViewModel(), settings: SettingsViewModel = koinViewModel()) {
    val nav = LocalEpisodeNav.current
    val items by vm.items.collectAsState()
    val used by vm.usedBytes.collectAsState()
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
