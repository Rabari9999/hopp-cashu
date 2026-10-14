package space.sovereigncast.hopp.ui.library

import space.sovereigncast.hopp.resources.cancel
import space.sovereigncast.hopp.resources.mark_played_older_confirm
import space.sovereigncast.hopp.resources.mark_played_older
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.resources.inbox_dismiss
import space.sovereigncast.hopp.resources.np_pause
import space.sovereigncast.hopp.resources.np_play
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
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
import space.sovereigncast.hopp.resources.favorite_add
import space.sovereigncast.hopp.resources.favorite_remove
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

/** What tapping an episode does (device feedback 2026-10-08, as in Pocket
 *  Casts): the row opens the episode's page; its round button plays. */
class EpisodeNav(val open: (EpisodeItem) -> Unit, val play: (EpisodeItem) -> Unit)
val LocalEpisodeNav = staticCompositionLocalOf { EpisodeNav({}, {}) }

/**
 * The round play button of a row: how far it is heard as a ring around it,
 * pause while this episode plays.
 */
@Composable
fun PlayButton(item: EpisodeItem) {
    val player: PlayerController = koinInject()
    val nav = LocalEpisodeNav.current
    val haptics = LocalHapticFeedback.current
    // Only play/pause changes this, not every position tick.
    val playingId by remember(player) { player.state.map { if (it.isPlaying) it.itemId else null }.distinctUntilChanged() }.collectAsState(null)
    val playing = playingId == item.id.toString()
    val duration = (item.durationSeconds ?: 0) * 1000
    val heard = if (item.played) 1f else if (duration > 0) (item.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val label = stringResource(if (playing) Res.string.np_pause else Res.string.np_play)
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .clickable(onClickLabel = label) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (playing) player.engine.pause() else nav.play(item)
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { heard }, modifier = Modifier.size(34.dp), strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurface, trackColor = space.sovereigncast.hopp.ui.theme.Hopp.colors.progressTrack,
            gapSize = 0.dp,
        )
        Icon(if (playing) HoppIcons.Pause else HoppIcons.Play, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** The one EpisodeActionsViewModel of the app, provided by the frame — rows
 *  used to look it up each, which costs on every row scrolled into view. */
val LocalEpisodeActions = staticCompositionLocalOf<EpisodeActionsViewModel?> { null }

/** The ⋮ of every episode row (ADR 0012): up next, download, played. */
@Composable
fun EpisodeMenu(item: EpisodeItem, vm: EpisodeActionsViewModel = LocalEpisodeActions.current ?: koinViewModel()) {
    var open by remember { mutableStateOf(false) }
    var confirmOlder by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, stringResource(Res.string.menu_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun pick(action: () -> Unit) { open = false; action() }
            if (item.inInbox) DropdownMenuItem(text = { Text(stringResource(Res.string.inbox_dismiss)) }, onClick = { pick { vm.dismiss(item) } })
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
            // This one and every older one (device feedback 2026-10-14, as in Castamatic).
            DropdownMenuItem(text = { Text(stringResource(Res.string.mark_played_older)) }, onClick = { pick { confirmOlder = true } })
            DropdownMenuItem(
                text = { Text(stringResource(if (item.favorite) Res.string.favorite_remove else Res.string.favorite_add)) },
                onClick = { pick { vm.setFavorite(item, !item.favorite) } },
            )
        }
    }
    if (confirmOlder) AlertDialog(
        onDismissRequest = { confirmOlder = false },
        text = { Text(stringResource(Res.string.mark_played_older_confirm, item.title)) },
        confirmButton = { TextButton(onClick = { confirmOlder = false; vm.setPlayedUpTo(item) }) { Text(stringResource(Res.string.mark_played)) } },
        dismissButton = { TextButton(onClick = { confirmOlder = false }) { Text(stringResource(Res.string.cancel)) } },
    )
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
    val haptics = LocalHapticFeedback.current
    val reorderable = rememberReorderableLazyListState(listState) { from, to ->
        order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
