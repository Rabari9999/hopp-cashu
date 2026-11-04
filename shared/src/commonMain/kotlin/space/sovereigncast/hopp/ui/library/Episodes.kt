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
import space.sovereigncast.hopp.resources.menu_save_audio
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
import sh.calvin.reorderable.ReorderableColumn
import androidx.compose.foundation.layout.Column
import sh.calvin.reorderable.ReorderableItem
import androidx.compose.runtime.key
import space.sovereigncast.hopp.resources.queue_more
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
import space.sovereigncast.hopp.resources.menu_download_now
import space.sovereigncast.hopp.resources.menu_download
import space.sovereigncast.hopp.resources.menu_more
import space.sovereigncast.hopp.resources.menu_play_last
import space.sovereigncast.hopp.resources.menu_play_next
import space.sovereigncast.hopp.resources.menu_remove_download
import space.sovereigncast.hopp.resources.queue_drag
import space.sovereigncast.hopp.resources.queue_remove
import space.sovereigncast.hopp.ui.LocalPlatformActions
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.formatBytes
import androidx.compose.foundation.layout.padding

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

/**
 * The file's size the feed states, for an episode not on the device and not
 * waiting for its download (user request 2026-10-18): to choose between
 * downloading it now, streaming it, or waiting for Wi-Fi. Feeds that state a
 * few bytes — placeholders — show nothing.
 */
fun episodeSize(item: EpisodeItem, language: String = space.sovereigncast.hopp.ui.components.Languages.current()): String? {
    val bytes = item.enclosureLength?.takeIf { item.downloadState == DownloadState.NONE && it in PLAUSIBLE_SIZE } ?: return null
    return if (bytes < 1024L * 1024) "< 1 MB" else formatBytes(bytes, language)
}

/** Smaller than a short clip or larger than Hopp downloads: not the file's size. */
private val PLAUSIBLE_SIZE = 100_000L..(4L shl 30)

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
    val playingId by remember(player) { player.state.map { if (it.isPlaying || it.isBuffering) it.itemId else null }.distinctUntilChanged() }.collectAsState(null)
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

/** The ⋮ of every episode row (ADR 0012): up next, download, played — and the favourite, where no heart
 *  beside it says so already ([favorite] false: the episode's page, maintainer 2026-11-03). */
@Composable
fun EpisodeMenu(item: EpisodeItem, favorite: Boolean = true, vm: EpisodeActionsViewModel = LocalEpisodeActions.current ?: koinViewModel()) {
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
            val actions = LocalPlatformActions.current
            // Nothing is downloaded in the browser; the audio file can be saved instead (maintainer 2026-10-28).
            if (!actions.canDownload && actions.canSaveAudio) item.enclosureUrl?.let { url ->
                DropdownMenuItem(text = { Text(stringResource(Res.string.menu_save_audio)) }, onClick = { pick {
                    val ext = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 } ?: "mp3"
                    actions.saveAudio(url, space.sovereigncast.hopp.data.episodeFileName(item.podcastTitle.orEmpty(), item.title, ext))
                } })
            }
            if (actions.canDownload) when (item.downloadState) {
                DownloadState.DONE -> DropdownMenuItem(text = { Text(stringResource(Res.string.menu_remove_download)) }, onClick = { pick { vm.removeDownload(item) } })
                DownloadState.QUEUED, DownloadState.DOWNLOADING -> {
                    // Waiting for Wi-Fi: load it now, over mobile data too.
                    if (item.downloadState == DownloadState.QUEUED)
                        DropdownMenuItem(text = { Text(stringResource(Res.string.menu_download_now)) }, onClick = { pick { vm.downloadNow(item) } })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.menu_cancel_download)) }, onClick = { pick { vm.removeDownload(item) } })
                }
                else -> if (item.enclosureUrl != null)
                    DropdownMenuItem(text = { Text(stringResource(Res.string.menu_download)) }, onClick = { pick { vm.download(item) } })
            }
            DropdownMenuItem(
                text = { Text(stringResource(if (item.played) Res.string.mark_unplayed else Res.string.mark_played)) },
                onClick = { pick { vm.setPlayed(item, !item.played) } },
            )
            // This one and every older one (device feedback 2026-10-14, as in Castamatic).
            DropdownMenuItem(text = { Text(stringResource(Res.string.mark_played_older)) }, onClick = { pick { confirmOlder = true } })
            if (favorite) DropdownMenuItem(
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
 * The first [limit] of "Up next" where other things are on the page too — Home and the library
 * (Dennis, maintainer 2026-11-02: two taps to the list he cares most about were too many):
 * reorderable right there by the handle, the rest of the queue keeping its place behind them.
 * Not a lazy list of its own, so it sits in Home's list and in the library's grid alike.
 */
@Composable
fun QueuePreview(items: List<EpisodeItem>, limit: Int, onOpen: (EpisodeItem) -> Unit, onReorder: (List<Long>) -> Unit) {
    var shown by remember { mutableStateOf(items.take(limit)) }
    LaunchedEffect(items, limit) { shown = items.take(limit) }
    val haptics = LocalHapticFeedback.current
    val dragLabel = stringResource(Res.string.queue_drag)
    // One column: in a lazy list's item, siblings would lie on top of each other.
    Column {
    ReorderableColumn(
        shown,
        onSettle = { from, to ->
            shown = shown.toMutableList().apply { add(to, removeAt(from)) }
            onReorder(shown.map { it.id } + items.drop(limit).map { it.id })
        },
        onMove = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
    ) { _, item, dragging ->
        key(item.id) {
            ReorderableItem {
                // On the page's own ground; lifted only while dragged.
                Surface(color = if (dragging) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent, tonalElevation = if (dragging) 4.dp else 0.dp) {
                    // Opaque over what a swipe shows beneath it.
                    PlaylistEntry(
                        item, items.map { it.id }, onClick = onOpen, onOpen = onOpen,
                        color = if (dragging) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background,
                        before = { PlayButton(item) },
                    ) {
                        IconButton(onClick = {}, modifier = Modifier.draggableHandle().semantics { contentDescription = dragLabel }) {
                            Icon(HoppIcons.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (items.size > limit) Text(
        stringResource(Res.string.queue_more, items.size - limit), Modifier.padding(start = 16.dp, top = 2.dp, bottom = 6.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    }
}

/**
 * The playlist after "Now" (ADR 0012), reorderable by dragging the handle. The order is
 * kept locally while dragging and written once the drag ends. An entry leaves it by a swipe
 * or from its actions ([PlaylistEntry]). [header] and [footer]: items of their own around
 * it — "Now" and the like — which stay where they are. [onOpen]: the episode's page, where
 * a tap does something else ([onPlay]).
 */
@Composable
fun QueueList(
    items: List<EpisodeItem>, onPlay: (EpisodeItem) -> Unit, onReorder: (List<Long>) -> Unit,
    onOpen: ((EpisodeItem) -> Unit)? = onPlay,
    header: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {}, footer: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {},
) {
    var order by remember { mutableStateOf(items) }
    LaunchedEffect(items) { order = items }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    // By key: the header's items come before the episodes.
    val reorderable = rememberReorderableLazyListState(listState) { from, to ->
        val f = order.indexOfFirst { it.id == from.key }
        val t = order.indexOfFirst { it.id == to.key }
        if (f < 0 || t < 0) return@rememberReorderableLazyListState
        order = order.toMutableList().apply { add(t, removeAt(f)) }
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val dragLabel = stringResource(Res.string.queue_drag)
    LazyColumn(state = listState) {
        header()
        items(order, key = { it.id }) { item ->
            ReorderableItem(reorderable, key = item.id) { dragging ->
                Surface(tonalElevation = if (dragging) 4.dp else 0.dp) {
                    PlaylistEntry(
                        item, items.map { it.id }, onClick = onPlay, onOpen = onOpen,
                        color = if (dragging) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface,
                    ) {
                        IconButton(
                            onClick = {},
                            modifier = Modifier.draggableHandle(onDragStopped = { onReorder(order.map { it.id }) })
                                .semantics { contentDescription = dragLabel },
                        ) { Icon(HoppIcons.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        footer()
    }
}
