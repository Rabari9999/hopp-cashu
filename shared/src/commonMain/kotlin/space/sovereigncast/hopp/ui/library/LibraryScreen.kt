package space.sovereigncast.hopp.ui.library

import space.sovereigncast.hopp.ui.sovereigncast.ScBadge
import space.sovereigncast.hopp.ui.components.MEDIUM_WIDTH
import space.sovereigncast.hopp.sovereigncast.SovereigncastRepository
import space.sovereigncast.hopp.resources.show_episodes
import space.sovereigncast.hopp.resources.library_new
import space.sovereigncast.hopp.resources.library_as_list
import space.sovereigncast.hopp.resources.library_as_cards
import space.sovereigncast.hopp.data.ShowCounts
import space.sovereigncast.hopp.data.Show
import org.koin.compose.koinInject
import kotlinx.coroutines.flow.map
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import space.sovereigncast.hopp.resources.downloads_title
import space.sovereigncast.hopp.resources.queue_title
import space.sovereigncast.hopp.ui.components.HoppIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.library_continue
import space.sovereigncast.hopp.resources.library_empty
import space.sovereigncast.hopp.resources.library_shows
import space.sovereigncast.hopp.resources.inbox_no_shows

/** "Library" (ADR 0012): up next, downloads, the subscribed shows.
 *  "Continue listening" moved to Home. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onShow: (Long) -> Unit,
    onQueue: () -> Unit,
    onDownloads: () -> Unit,
    vm: LibraryViewModel = koinViewModel(),
) {
    val shows by vm.shows.collectAsState()
    val queueCount by vm.queueCount.collectAsState()
    val downloadCount by vm.downloadCount.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val counts by vm.counts.collectAsState()
    val asList by vm.asList.collectAsState()
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh) {
        val list = shows ?: return@PullToRefreshBox
        if (list.isEmpty()) {
            EmptyState(stringResource(Res.string.library_empty), stringResource(Res.string.inbox_no_shows))
            return@PullToRefreshBox
        }
        // Device feedback 2026-10-13: on a large tablet 104 dp covers in cells that
        // grew around them had little presence. Cards now fill their cell — 104 dp
        // at least on a phone, 168 dp from a tablet's width (ADR 0020) — or, the
        // listener's choice as in Pocket Casts, a list with room for the numbers.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val large = maxWidth >= MEDIUM_WIDTH
            val cells = when {
                asList -> GridCells.Adaptive(420.dp)
                large -> GridCells.Adaptive(168.dp)
                else -> GridCells.Adaptive(104.dp)
            }
            LazyVerticalGrid(cells, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) { EntryRow(HoppIcons.Queue, stringResource(Res.string.queue_title), queueCount, onQueue) }
                item(span = { GridItemSpan(maxLineSpan) }) { EntryRow(HoppIcons.Download, stringResource(Res.string.downloads_title), downloadCount, onDownloads) }
                item(span = { GridItemSpan(maxLineSpan) }) { ShowsHeader(asList, vm::showAsList) }
                items(list, key = { it.id }) { show ->
                    val c = counts[show.id]
                    if (asList) ShowListRow(show, c, cover = if (large) 88.dp else 64.dp) { onShow(show.id) }
                    else ShowCard(show, c, large) { onShow(show.id) }
                }
            }
        }
    }
}

/** "Shows", and the switch between cards and a list. */
@Composable
private fun ShowsHeader(asList: Boolean, onAsList: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.library_shows), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        val shape = RoundedCornerShape(50)
        Row(Modifier.clip(shape).border(1.dp, colors.outlineVariant, shape)) {
            listOf(false to (HoppIcons.Grid to Res.string.library_as_cards), true to (Icons.AutoMirrored.Rounded.List to Res.string.library_as_list))
                .forEach { (list, look) ->
                    val label = stringResource(look.second)
                    Box(
                        Modifier.background(if (list == asList) colors.secondaryContainer else Color.Transparent)
                            .selectable(selected = list == asList, role = Role.Tab, onClick = { onAsList(list) })
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .semantics { contentDescription = label },
                    ) { Icon(look.first, null, Modifier.size(20.dp)) }
                }
        }
    }
}

/** New episodes of a show, as a small count in the primary colour; nothing when there are none. */
@Composable
private fun NewCount(n: Int) {
    if (n <= 0) return
    val label = stringResource(Res.string.library_new, n)
    Text(
        "$n",
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 7.dp, vertical = 1.dp).semantics { contentDescription = label },
        color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
    )
}

/** A show as a card: the cover fills the cell, new episodes on it, title and author below. */
@Composable
private fun ShowCard(show: Show, counts: ShowCounts?, large: Boolean, onClick: () -> Unit) {
    val corner = if (large) 18.dp else 16.dp
    // Round at the top like the cover, barely at the bottom: rounded there too, the
    // card's corner cut into the author's line (as it once did on Home's strip).
    Column(
        Modifier.padding(8.dp).clip(RoundedCornerShape(topStart = corner, topEnd = corner, bottomStart = 4.dp, bottomEnd = 4.dp))
            .clickable(onClick = onClick).padding(bottom = 4.dp),
    ) {
        Box {
            CoverFill(show.imageUrl, corner = corner)
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { NewCount(counts?.new ?: 0) }
        }
        Text(
            show.title, Modifier.padding(top = 6.dp),
            style = if (large) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        show.author?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A show as a row of the list: cover, title, author, its episodes and whether it is a Sovereigncast show. */
@Composable
private fun ShowListRow(show: Show, counts: ShowCounts?, cover: Dp, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val sc: SovereigncastRepository = koinInject()
    val isSc by remember(show.id) { sc.layer(show.id).map { it != null } }.collectAsState(false)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(show.imageUrl, cover, corner = 14.dp)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(show.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            show.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                counts?.let { Text(stringResource(Res.string.show_episodes, it.episodes), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                if (isSc) ScBadge()
            }
        }
        NewCount(counts?.new ?: 0)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun Header(text: String) = Text(
    text, style = MaterialTheme.typography.titleMedium,
    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
)

@Composable
private fun EntryRow(icon: ImageVector, label: String, count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(16.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (count > 0) Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
