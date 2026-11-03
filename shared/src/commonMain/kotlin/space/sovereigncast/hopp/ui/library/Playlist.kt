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
 * far it is — the play button plays or pauses it; a tap elsewhere opens the episode.
 */
@Composable
fun PlaylistNowCard(item: EpisodeItem, onOpen: (EpisodeItem) -> Unit, modifier: Modifier = Modifier, player: PlayerController = koinInject()) {
    val colors = MaterialTheme.colorScheme
    val nav = LocalEpisodeNav.current
    val state by player.state.collectAsState()
    val loaded = state.itemId == item.id.toString()
    val playing = loaded && (state.isPlaying || state.isBuffering)
    val duration = if (loaded && state.durationMs > 0) state.durationMs else (item.durationSeconds ?: 0) * 1000
    val position = if (loaded) state.positionMs else item.positionMs
    val label = stringResource(if (playing) Res.string.np_pause else Res.string.np_play)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant)
            .clickable { onOpen(item) }.padding(10.dp),
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
        now?.let { PlaylistNowCard(it, onOpen) }
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
