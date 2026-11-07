package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.data.DownloadState
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.resources.mark_played
import space.sovereigncast.hopp.resources.menu_cancel_download
import space.sovereigncast.hopp.resources.menu_download
import space.sovereigncast.hopp.resources.menu_more
import space.sovereigncast.hopp.resources.menu_play_last
import space.sovereigncast.hopp.resources.menu_play_next
import space.sovereigncast.hopp.resources.menu_remove_download
import space.sovereigncast.hopp.resources.playlist_open
import space.sovereigncast.hopp.resources.playlist_removed
import space.sovereigncast.hopp.resources.playlist_skip
import space.sovereigncast.hopp.resources.playlist_undo
import space.sovereigncast.hopp.resources.queue_remove
import space.sovereigncast.hopp.ui.LocalPlatformActions
import space.sovereigncast.hopp.ui.components.onSecondaryClick
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.np_pause
import space.sovereigncast.hopp.resources.np_play
import space.sovereigncast.hopp.resources.playlist_after
import space.sovereigncast.hopp.resources.playlist_now_paused
import space.sovereigncast.hopp.resources.queue_empty_text
import space.sovereigncast.hopp.resources.playlist_now_playing
import space.sovereigncast.hopp.resources.remaining
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.formatTime

/*
 * The playlist (Dennis, maintainer 2026-11-03): "Continue listening" and "Up next" as one list — on top
 * the episode the player has, "Now", which is no part of the queue and cannot be moved; below it the
 * queue in the listener's order. Home, the library, its own page and now playing show the same.
 */

/** The playlist's "Now": what the player has, unless it is heard to its end; null for none. */
@Composable
fun playlistNow(player: PlayerController = koinInject()): EpisodeItem? {
    val now by player.current.collectAsState()
    val ended by remember(player) {
        player.state.map { s -> s.durationMs > 0 && s.positionMs >= s.durationMs - 1_500 && !s.isPlaying }.distinctUntilChanged()
    }.collectAsState(false)
    return now?.item?.takeIf { !it.played && !ended }
}

/** What the playlist still has to play, [now] from where it is — for its head; null when no length is known. */
internal fun playlistLeftMs(now: EpisodeItem?, nowPositionMs: Long?, queue: List<EpisodeItem>): Long? {
    val all = listOfNotNull(now?.let { it to (nowPositionMs ?: it.positionMs) }) + queue.map { it to it.positionMs }
    val known = all.mapNotNull { (e, pos) -> e.durationSeconds?.let { (it * 1000 - pos).coerceAtLeast(0) } }
    return known.takeIf { it.isNotEmpty() }?.sum()
}

/**
 * "Now", a card at the playlist's top: cover, playing or paused, the title, how much is left and how
 * far it is — the play button plays or pauses it; a tap elsewhere opens the episode, a long press what
 * else there is ([PlaylistActions]: [hasNext], it can be skipped).
 */
@Composable
fun PlaylistNowCard(item: EpisodeItem, onOpen: (EpisodeItem) -> Unit, modifier: Modifier = Modifier, hasNext: Boolean = false, player: PlayerController = koinInject()) {
    val colors = MaterialTheme.colorScheme
    var actions by remember { mutableStateOf(false) }
    if (actions) PlaylistActions(item, onDismiss = { actions = false }, onOpen = onOpen, onSkip = if (hasNext) player::skipEpisode else null)
    val moreLabel = stringResource(Res.string.menu_more)
    val nav = LocalEpisodeNav.current
    val state by player.state.collectAsState()
    val loaded = state.itemId == item.id.toString()
    val playing = loaded && (state.isPlaying || state.isBuffering)
    val duration = if (loaded && state.durationMs > 0) state.durationMs else (item.durationSeconds ?: 0) * 1000
    val position = if (loaded) state.positionMs else item.positionMs
    val label = stringResource(if (playing) Res.string.np_pause else Res.string.np_play)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant)
            .onSecondaryClick { actions = true }.combinedClickable(onLongClick = { actions = true }) { onOpen(item) }
            .semantics { customActions = listOf(CustomAccessibilityAction(moreLabel) { actions = true; true }) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(item.imageUrl ?: item.podcastImageUrl, 56.dp, corner = 10.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(if (playing) Res.string.playlist_now_playing else Res.string.playlist_now_paused).uppercase(),
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = space.sovereigncast.hopp.ui.theme.Hopp.colors.valueText,
            )
            Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(item.podcastTitle, duration.takeIf { it > 0 }?.let { stringResource(Res.string.remaining, formatTime((it - position).coerceAtLeast(0))) })
            if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (duration > 0) LinearProgressIndicator(
                progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = space.sovereigncast.hopp.ui.theme.Hopp.colors.value,
                trackColor = colors.outlineVariant, gapSize = 0.dp, drawStopIndicator = {},
            )
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(colors.onSurface)
                .clickable(onClickLabel = label) { if (playing) player.engine.pause() else nav.play(item) }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { Icon(if (playing) HoppIcons.Pause else HoppIcons.Play, null, Modifier.size(18.dp), tint = colors.surface) }
    }
}

/**
 * The playlist where other things are on the page too — Home and the library: "Now", then the first
 * [limit] after it, reorderable right there ([QueuePreview]); a line when there is nothing at all.
 */
@Composable
fun PlaylistBody(now: EpisodeItem?, after: List<EpisodeItem>, limit: Int, onOpen: (EpisodeItem) -> Unit, onReorder: (List<Long>) -> Unit) {
    Column {
        if (now == null && after.isEmpty()) {
            Text(stringResource(Res.string.queue_empty_text), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        now?.let { PlaylistNowCard(it, onOpen, hasNext = after.isNotEmpty()) }
        if (now != null && after.isNotEmpty()) PlaylistAfterLabel()
        if (after.isNotEmpty()) QueuePreview(after, limit, onOpen = onOpen, onReorder = onReorder)
    }
}

/** "Next", over what follows "Now". */
@Composable
internal fun PlaylistAfterLabel() = Text(
    stringResource(Res.string.playlist_after).uppercase(), Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** Where "Removed · Undo" shows: the frame's, or a sheet's own — one under a sheet is not seen. */
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState?> { null }

/**
 * One entry of the playlist after "Now" (maintainer 2026-11-04: an episode to skip could only be
 * moved, not taken out). Swiped to the left it leaves the playlist, and "Undo" puts it back where it
 * was; a long press — a right-click — opens what else there is ([PlaylistActions]); in the browser a ⋮
 * does too, where a mouse does not swipe. A screen reader has both as actions of the row.
 * [onOpen]: the episode's page, where there is one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistEntry(
    item: EpisodeItem, onClick: (EpisodeItem) -> Unit, onOpen: ((EpisodeItem) -> Unit)?,
    color: Color, app: AppInfo = koinInject(), before: @Composable () -> Unit = {}, handle: @Composable () -> Unit,
) {
    val vm = LocalEpisodeActions.current ?: koinViewModel()
    val snackbar = LocalSnackbar.current
    var actions by remember { mutableStateOf(false) }
    val removed = stringResource(Res.string.playlist_removed)
    val undo = stringResource(Res.string.playlist_undo)
    val remove = {
        vm.removeFromQueue(item) {
            val host = snackbar ?: return@removeFromQueue false
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(removed, undo, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed
        }
        Unit
    }
    val removeLabel = stringResource(Res.string.queue_remove)
    val moreLabel = stringResource(Res.string.menu_more)
    val swipe = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        swipe,
        backgroundContent = {
            val colors = MaterialTheme.colorScheme
            Box(
                Modifier.fillMaxSize().background(if (swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart) colors.errorContainer else Color.Transparent)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Rounded.Delete, null, tint = colors.onErrorContainer) }
        },
        enableDismissFromStartToEnd = false,
        onDismiss = { remove() },
    ) {
        // Opaque over what a swipe shows beneath it, though the page's row is not on a glowing ground.
        val swiped = swipe.dismissDirection != SwipeToDismissBoxValue.Settled
        Box(Modifier.background(if (swiped) MaterialTheme.colorScheme.background else color)) {
            // On the row itself, the node a screen reader stops at.
            val a11y = Modifier.semantics {
                customActions = listOf(
                    CustomAccessibilityAction(removeLabel) { remove(); true },
                    CustomAccessibilityAction(moreLabel) { actions = true; true },
                )
            }
            EpisodeRow(item, showPodcast = true, onClick = { onClick(item) }, modifier = a11y, onLongClick = { actions = true }) {
                before()
                if (app.isWeb) IconButton(onClick = { actions = true }) {
                    Icon(Icons.Rounded.MoreVert, moreLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                handle()
            }
        }
    }
    if (actions) PlaylistActions(item, onDismiss = { actions = false }, onOpen = onOpen, onRemove = remove)
}

/**
 * What else there is for an entry of the playlist (maintainer 2026-11-04), in a sheet: [onSkip] for
 * "Now" — the next one plays; up next or last; heard; the download; out of the playlist; its page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistActions(
    item: EpisodeItem, onDismiss: () -> Unit, onOpen: ((EpisodeItem) -> Unit)?,
    onRemove: (() -> Unit)? = null, onSkip: (() -> Unit)? = null,
) {
    val vm = LocalEpisodeActions.current ?: koinViewModel()
    val platform = LocalPlatformActions.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            item.title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        @Composable
        fun option(text: String, action: () -> Unit) = Text(
            text, Modifier.fillMaxWidth().clickable { onDismiss(); action() }.padding(horizontal = 24.dp, vertical = 14.dp),
        )
        onSkip?.let { option(stringResource(Res.string.playlist_skip), it) }
        if (onRemove != null) {
            option(stringResource(Res.string.menu_play_next)) { vm.playNext(item) }
            option(stringResource(Res.string.menu_play_last)) { vm.playLast(item) }
        }
        // Heard, it leaves the playlist too.
        option(stringResource(Res.string.mark_played)) { vm.setPlayed(item, true) }
        if (platform.canDownload) when (item.downloadState) {
            DownloadState.DONE -> option(stringResource(Res.string.menu_remove_download)) { vm.removeDownload(item) }
            DownloadState.QUEUED, DownloadState.DOWNLOADING -> option(stringResource(Res.string.menu_cancel_download)) { vm.removeDownload(item) }
            else -> if (item.enclosureUrl != null) option(stringResource(Res.string.menu_download)) { vm.download(item) }
        }
        onRemove?.let { option(stringResource(Res.string.queue_remove), it) }
        onOpen?.let { open -> option(stringResource(Res.string.playlist_open)) { open(item) } }
        Spacer(Modifier.height(24.dp))
    }
}
