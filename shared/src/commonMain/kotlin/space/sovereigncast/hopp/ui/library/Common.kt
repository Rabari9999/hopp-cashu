package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import space.sovereigncast.hopp.resources.cancel
import space.sovereigncast.hopp.resources.episode_search
import space.sovereigncast.hopp.resources.episode_search_found
import space.sovereigncast.hopp.resources.episode_search_none
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import space.sovereigncast.hopp.data.DownloadState
import space.sovereigncast.hopp.ui.components.HoppIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.played
import space.sovereigncast.hopp.resources.remaining
import space.sovereigncast.hopp.ui.components.formatDate
import space.sovereigncast.hopp.ui.components.formatTime
import space.sovereigncast.hopp.ui.theme.Hopp

@Composable
fun Cover(url: String?, size: Dp, corner: Dp = 12.dp, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** A cover as wide as its place, and as tall — a card in the library's grid. */
@Composable
fun CoverFill(url: String?, corner: Dp = 18.dp, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** One episode in a list: cover, show (optional), title, date · duration or
 *  time left. How far it is heard shows as the ring of its play button. */
@Composable
fun EpisodeRow(
    item: EpisodeItem,
    showPodcast: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The show's cover where [item] comes without it — an episode without its own cover shows the show's. */
    showCover: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    // The one shown beside the list, when the show and an episode stand side by side.
    val shown = LocalShownEpisode.current == item.id
    Row(
        modifier.fillMaxWidth().then(if (shown) Modifier.background(colors.secondaryContainer) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            Cover(item.imageUrl ?: item.podcastImageUrl ?: showCover, 56.dp)
            DownloadBadge(item, Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp))
        }
        Column(Modifier.weight(1f)) {
            if (showPodcast && item.podcastTitle != null) {
                Text(item.podcastTitle, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (item.played) colors.onSurfaceVariant else colors.onSurface,
            )
            val duration = item.durationSeconds?.times(1000)
            val meta = buildList {
                formatDate(item.publishedAt).takeIf { it.isNotEmpty() }?.let(::add)
                when {
                    item.played -> add(stringResource(Res.string.played))
                    item.positionMs > 0 && duration != null -> add(stringResource(Res.string.remaining, formatTime(duration - item.positionMs)))
                    duration != null -> add(formatTime(duration))
                }
                // Downloaded (or on the way), else how large it is.
                (downloadLabel(item) ?: episodeSize(item))?.let(::add)
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        trailing()
    }
}

/**
 * On the cover's corner, what is on the device (device feedback 2026-10-15,
 * AntennaPod for comparison): a tick for downloaded, the progress while it
 * loads, "!" when it failed; nothing for an episode that streams. Play stays
 * the row's button — Hopp streams what is not downloaded.
 */
@Composable
fun DownloadBadge(item: EpisodeItem, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val state = item.downloadState
    if (state == DownloadState.NONE) return
    val label = downloadLabel(item).orEmpty()
    val progress = LocalDownloadProgress.current[item.id]
    // Waiting is quiet (a list may hold many); dark is on the device.
    val waiting = state == DownloadState.QUEUED || (state == DownloadState.DOWNLOADING && progress == null)
    val fill = when {
        state == DownloadState.FAILED -> colors.error
        waiting -> colors.surfaceVariant
        else -> colors.onSurface
    }
    val on = when {
        state == DownloadState.FAILED -> colors.onError
        waiting -> colors.onSurfaceVariant
        else -> colors.surface
    }
    Box(
        modifier.size(24.dp).border(2.dp, colors.surface, CircleShape).padding(2.dp).clip(CircleShape).background(fill)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when {
            state == DownloadState.DONE -> Icon(HoppIcons.Downloaded, null, Modifier.size(14.dp), tint = on)
            state == DownloadState.FAILED -> Text("!", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = on)
            waiting -> Icon(HoppIcons.Download, null, Modifier.size(14.dp), tint = on)
            else -> CircularProgressIndicator(progress = { progress ?: 0f }, Modifier.size(13.dp), color = on, strokeWidth = 2.dp, trackColor = on.copy(alpha = 0.3f), gapSize = 0.dp)
        }
    }
}

@Composable
fun EmptyState(title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            action?.invoke()
        }
    }
}

/**
 * Calls [onNearEnd] when the list is scrolled to within a few items of its
 * end, with the number of items that layout had. The caller compares it with
 * what it has loaded since: a near end seen while the list was still empty
 * must not load the next page once the first has arrived meanwhile — which
 * it did, now and then, on a show page just opened (40 episodes, not 20).
 */
@Composable
fun OnNearEnd(state: androidx.compose.foundation.lazy.LazyListState, onNearEnd: (seenItems: Int) -> Unit) {
    // The item count while near the end, -1 otherwise — so a longer list that
    // is still near its end (all of it on screen) asks again.
    val near by androidx.compose.runtime.remember(state) {
        androidx.compose.runtime.derivedStateOf {
            val info = state.layoutInfo
            if (info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4) info.totalItemsCount else -1
        }
    }
    androidx.compose.runtime.LaunchedEffect(near) { if (near > 0) onNearEnd(near) }
}

/** Searching a show's episodes by topic (device feedback 2026-10-11): the
 *  field above the list, and how many it found. */
@Composable
fun EpisodeSearchField(query: String, onChange: (String) -> Unit, found: Int?) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        androidx.compose.material3.OutlinedTextField(
            query, onChange, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(stringResource(Res.string.episode_search)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) androidx.compose.material3.IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Rounded.Clear,
                        stringResource(Res.string.cancel))
                }
            },
        )
        if (found != null) Text(
            if (found == 0) stringResource(Res.string.episode_search_none)
            else pluralStringResource(Res.plurals.episode_search_found, found, found),
            Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
        )
    }
}
