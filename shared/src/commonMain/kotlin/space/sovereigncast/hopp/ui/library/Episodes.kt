package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import space.sovereigncast.hopp.data.DownloadState
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.dl_done
import space.sovereigncast.hopp.resources.dl_failed
import space.sovereigncast.hopp.resources.dl_progress
import space.sovereigncast.hopp.resources.dl_waiting
import space.sovereigncast.hopp.resources.mark_played
import space.sovereigncast.hopp.resources.mark_unplayed
import space.sovereigncast.hopp.resources.menu_cancel_download
import space.sovereigncast.hopp.resources.menu_download
import space.sovereigncast.hopp.resources.menu_more
import space.sovereigncast.hopp.resources.menu_play_last
import space.sovereigncast.hopp.resources.menu_play_next
import space.sovereigncast.hopp.resources.menu_remove_download
import space.sovereigncast.hopp.resources.queue_drag
import space.sovereigncast.hopp.resources.queue_remove
import space.sovereigncast.hopp.ui.components.HoppIcons

/** Episode id → download progress 0..1, provided once by the app frame. */
val LocalDownloadProgress = compositionLocalOf<Map<Long, Float>> { emptyMap() }

/** "Downloaded", "Downloading · 42 %" … for the row's meta line; null when
 *  there is nothing to say. */
@Composable
fun downloadLabel(item: EpisodeItem): String? = when (item.downloadState) {
    DownloadState.DONE -> stringResource(Res.string.dl_done)
    DownloadState.DOWNLOADING -> LocalDownloadProgress.current[item.id]
        ?.let { stringResource(Res.string.dl_progress, "${(it * 100).toInt()} %") } ?: stringResource(Res.string.dl_waiting)
    DownloadState.QUEUED -> stringResource(Res.string.dl_waiting)
    DownloadState.FAILED -> stringResource(Res.string.dl_failed)
    else -> null
}

/** The ⋮ of every episode row (ADR 0012): up next, download, played. */
@Composable
fun EpisodeMenu(item: EpisodeItem, vm: EpisodeActionsViewModel = koinViewModel()) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, stringResource(Res.string.menu_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun pick(action: () -> Unit) { open = false; action() }
            DropdownMenuItem(text = { Text(stringResource(Res.string.menu_play_next)) }, onClick = { pick { vm.playNext(item) } })
            DropdownMenuItem(text = { Text(stringResource(Res.string.menu_play_last)) }, onClick = { pick { vm.playLast(item) } })
            when (item.downloadState) {
                DownloadState.DONE -> DropdownMenuItem(text = { Text(stringResource(Res.string.menu_remove_download)) }, onClick = { pick { vm.removeDownload(item) } })
                DownloadState.QUEUED, DownloadState.DOWNLOADING ->
                    DropdownMenuItem(text = { Text(stringResource(Res.string.menu_cancel_download)) }, onClick = { pick { vm.removeDownload(item) } })
                else -> if (item.enclosureUrl != null)
                    DropdownMenuItem(text = { Text(stringResource(Res.string.menu_download)) }, onClick = { pick { vm.download(item) } })
            }
            DropdownMenuItem(
                text = { Text(stringResource(if (item.played) Res.string.mark_unplayed else Res.string.mark_played)) },
                onClick = { pick { vm.setPlayed(item, !item.played) } },
            )
        }
    }
}

/**
 * Up next, reorderable by dragging the handle (ADR 0012). The order is
 * kept locally while dragging and written once the drag ends.
 */
@Composable
fun QueueList(items: List<EpisodeItem>, onPlay: (EpisodeItem) -> Unit, onRemove: (EpisodeItem) -> Unit, onReorder: (List<Long>) -> Unit) {
    var order by remember { mutableStateOf(items) }
    LaunchedEffect(items) { order = items }
    val listState = rememberLazyListState()
    val reorderable = rememberReorderableLazyListState(listState) { from, to ->
        order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    val dragLabel = stringResource(Res.string.queue_drag)
    LazyColumn(state = listState) {
        items(order, key = { it.id }) { item ->
            ReorderableItem(reorderable, key = item.id) { dragging ->
                Surface(tonalElevation = if (dragging) 4.dp else 0.dp) {
                    EpisodeRow(item, showPodcast = true, onClick = { onPlay(item) }) {
                        IconButton(onClick = { onRemove(item) }) {
                            Icon(Icons.Rounded.Close, stringResource(Res.string.queue_remove), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = {},
                            modifier = Modifier.draggableHandle(onDragStopped = { onReorder(order.map { it.id }) })
                                .semantics { contentDescription = dragLabel },
                        ) { Icon(HoppIcons.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}
