package space.sovereigncast.hopp.ui.library

import space.sovereigncast.hopp.resources.home_days_ago
import space.sovereigncast.hopp.resources.library_yesterday
import space.sovereigncast.hopp.resources.library_today
import space.sovereigncast.hopp.resources.library_sort_newest
import space.sovereigncast.hopp.resources.library_sort_unheard
import space.sovereigncast.hopp.resources.library_sort_title
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlinx.datetime.daysUntil
import space.sovereigncast.hopp.ui.LocalPlatformActions
import space.sovereigncast.hopp.ui.components.formatDate
import space.sovereigncast.hopp.directory.Category
import space.sovereigncast.hopp.data.Settings
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import org.jetbrains.compose.resources.pluralStringResource
import space.sovereigncast.hopp.resources.show_all_played
import space.sovereigncast.hopp.resources.mark_played
import space.sovereigncast.hopp.resources.cancel
import space.sovereigncast.hopp.resources.library_all_played_confirm
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.draw.shadow
import space.sovereigncast.hopp.ui.sovereigncast.ScBadge
import space.sovereigncast.hopp.ui.components.MEDIUM_WIDTH
import space.sovereigncast.hopp.sovereigncast.SovereigncastRepository
import space.sovereigncast.hopp.resources.show_episodes
import space.sovereigncast.hopp.resources.library_unheard
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
import space.sovereigncast.hopp.ui.theme.Hopp
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
import space.sovereigncast.hopp.resources.home_all
import space.sovereigncast.hopp.resources.queue_empty
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout

/** "Library" (ADR 0012): up next, downloads, the subscribed shows — since 0.3.3 each a section
 *  that folds, as on Home, up next and the downloads right there (Dennis, maintainer 2026-11-02:
 *  two taps to the list he cares most about). "Continue listening" moved to Home. */
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
    val sort by vm.sort.collectAsState()
    val queue by vm.queueItems.collectAsState()
    val downloaded by vm.downloaded.collectAsState()
    val collapsed by vm.collapsed.collectAsState()
    val nav = LocalEpisodeNav.current
    var confirmAllPlayed by remember { mutableStateOf(false) }
    if (confirmAllPlayed) {
        val unheard = counts.values.sumOf { it.unheard }
        AlertDialog(
            onDismissRequest = { confirmAllPlayed = false },
            text = { Text(pluralStringResource(Res.plurals.library_all_played_confirm, unheard, unheard)) },
            confirmButton = { TextButton(onClick = { confirmAllPlayed = false; vm.markAllPlayed() }) { Text(stringResource(Res.string.mark_played)) } },
            dismissButton = { TextButton(onClick = { confirmAllPlayed = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
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
            val canDownload = LocalPlatformActions.current.canDownload
            LazyVerticalGrid(cells, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp)) {
                val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope) -> GridItemSpan = { GridItemSpan(it.maxLineSpan) }
                val queueOpen = LIB_QUEUE !in collapsed
                item(key = "h-queue", span = full) {
                    SectionHeader(stringResource(Res.string.queue_title), queueOpen, queueCount, { vm.toggle(LIB_QUEUE) },
                        stringResource(Res.string.home_all, queueCount).takeIf { queueCount > 0 }, onQueue, modifier = Modifier.bleed(8.dp))
                }
                if (queueOpen) item(key = "queue", span = full) {
                    if (queue.isEmpty()) Text(stringResource(Res.string.queue_empty), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    else Box(Modifier.bleed(8.dp)) { QueuePreview(queue, HomeViewModel.QUEUE_ROWS, onOpen = { nav.open(it) }, onReorder = vm::reorderQueue) }
                }
                if (canDownload && downloadCount > 0) {
                    val open = LIB_DOWNLOADS !in collapsed
                    item(key = "h-downloads", span = full) {
                        SectionHeader(stringResource(Res.string.downloads_title), open, downloadCount, { vm.toggle(LIB_DOWNLOADS) },
                            stringResource(Res.string.home_all, downloadCount), onDownloads, modifier = Modifier.bleed(8.dp))
                    }
                    if (open) items(downloaded.take(HomeViewModel.HOME_ROWS), key = { "d${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { item ->
                        Box(Modifier.bleed(8.dp)) { EpisodeRow(item, showPodcast = true, onClick = { nav.open(item) }) { PlayButton(item); EpisodeMenu(item) } }
                    }
                }
                val showsOpen = LIB_SHOWS !in collapsed
                item(key = "h-shows", span = full) {
                    ShowsHeader(asList, vm::showAsList, sort, vm::sortBy, counts.values.sumOf { it.unheard }, showsOpen, list.size, { vm.toggle(LIB_SHOWS) }) { confirmAllPlayed = true }
                }
                if (showsOpen) items(list, key = { it.id }) { show ->
                    val c = counts[show.id]
                    if (asList) ShowListRow(show, c, cover = if (large) 88.dp else 64.dp) { onShow(show.id) }
                    else ShowCard(show, c, large) { onShow(show.id) }
                }
            }
        }
    }
}

/**
 * "Shows", the order (device feedback 2026-10-19), and the switch between cards and a list; below,
 * while anything is unheard, how much and "Mark all as played" — a quiet line, at hand for a new
 * setup with hundreds unheard (maintainer 2026-11-01: a row of its own was too dominant).
 */
@Composable
private fun ShowsHeader(
    asList: Boolean, onAsList: (Boolean) -> Unit, sort: String, onSort: (String) -> Unit, unheard: Int,
    open: Boolean, count: Int, onToggle: () -> Unit, onAllPlayed: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 0f else -90f)
    val title = stringResource(Res.string.library_shows)
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp, bottom = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Folds like Home's sections: the title and its arrow.
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = title, onClick = onToggle)
                    .padding(vertical = 8.dp).semantics { stateDescription = if (open) "open" else "closed" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Icon(HoppIcons.ChevronDown, null, Modifier.padding(start = 4.dp).size(22.dp).rotate(turn), tint = colors.onSurfaceVariant)
                if (!open) Text(" $count", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
            if (!open) return@Row
            var open by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { open = true }) { Text(sortLabel(sort) + " ▾") }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    Settings.sortChoices.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(sortLabel(s), fontWeight = if (s == sort) FontWeight.Bold else null) },
                            leadingIcon = { if (s == sort) Icon(Icons.Rounded.Check, null) },
                            onClick = { open = false; onSort(s) },
                        )
                    }
                }
            }
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
        if (open && unheard > 0) Row(verticalAlignment = Alignment.CenterVertically) {
            val style = MaterialTheme.typography.bodySmall
            Text(stringResource(Res.string.library_unheard, unheard) + " · ", style = style, color = colors.onSurfaceVariant)
            Text(
                stringResource(Res.string.show_all_played),
                Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onAllPlayed).padding(vertical = 8.dp),
                style = style, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * Episodes of a show not heard yet, as a count on orange; nothing when there are
 * none (device feedback 2026-10-17: it counted only those in "New", and stayed
 * at 1 however many waited). Above 99 it says 99+. Device
 * feedback 2026-10-13, twice: in the primary colour — black on a light page — it
 * vanished on dark covers; then red with a ring in the page's colour it still had
 * little presence in the dark theme, where that ring is black too. Now a brighter
 * red, a white ring in both themes and a small shadow lift it off any cover — dark,
 * light or red — and the figure is a size larger. Since 2026-11-01 the logo's orange, the
 * figure black on it (ADR 0019): red always looked like an error (maintainer). Since 2026-11-03 without
 * the white ring (maintainer): the orange and the small shadow carry it.
 */
@Composable
private fun UnheardCount(n: Int) {
    if (n <= 0) return
    val label = stringResource(Res.string.library_unheard, n)
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.shadow(3.dp, shape).clip(shape).background(Hopp.colors.value)
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).padding(horizontal = 6.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (n > 99) "99+" else "$n", color = Hopp.colors.onValue, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
    }
}

/** A show as a card: the cover fills the cell, its unheard episodes on it, title and author below. */
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
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { UnheardCount(counts?.unheard ?: 0) }
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
            // When its newest episode came out and its category, quietly (device feedback 2026-10-19).
            val line = listOfNotNull(
                counts?.latestAt?.let { latestLabel(it) },
                Category.of(show.categories).firstOrNull()?.let { categoryLabel(it) },
            ).joinToString(" · ").ifEmpty { counts?.let { pluralStringResource(Res.plurals.show_episodes, it.episodes, it.episodes) }.orEmpty() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (line.isNotEmpty()) Text(line, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isSc) ScBadge()
            }
        }
        UnheardCount(counts?.unheard ?: 0)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun Header(text: String) = Text(
    text, style = MaterialTheme.typography.titleMedium,
    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
)

/** Folded sections of the library, kept with Home's. */
private const val LIB_QUEUE = "lib-queue"
private const val LIB_DOWNLOADS = "lib-downloads"
private const val LIB_SHOWS = "lib-shows"

/** As wide as the grid with its side padding: a row in line with the page, as on Home. */
private fun Modifier.bleed(by: androidx.compose.ui.unit.Dp) = layout { measurable, constraints ->
    val px = by.roundToPx()
    val wide = constraints.copy(minWidth = constraints.minWidth + 2 * px, maxWidth = constraints.maxWidth + 2 * px)
    val placeable = measurable.measure(wide)
    layout(constraints.maxWidth, placeable.height) { placeable.place(-px, 0) }
}

@Composable
private fun sortLabel(sort: String): String = stringResource(when (sort) {
    Settings.SORT_TITLE -> Res.string.library_sort_title
    Settings.SORT_UNHEARD -> Res.string.library_sort_unheard
    else -> Res.string.library_sort_newest
})

/** "Today", "Yesterday", "3 days ago" — calendar days on the listener's clock — then the date. */
@Composable
private fun latestLabel(epochSeconds: Long): String {
    val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
    val today = kotlin.time.Clock.System.todayIn(zone)
    val day = kotlin.time.Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(zone).date
    return when (val days = day.daysUntil(today)) {
        0 -> stringResource(Res.string.library_today)
        1 -> stringResource(Res.string.library_yesterday)
        in 2..13 -> stringResource(Res.string.home_days_ago, days)
        else -> formatDate(epochSeconds)
    }
}
