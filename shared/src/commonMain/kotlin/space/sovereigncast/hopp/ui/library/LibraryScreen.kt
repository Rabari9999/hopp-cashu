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
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import space.sovereigncast.hopp.resources.tags_all
import space.sovereigncast.hopp.resources.tags_untagged
import androidx.compose.ui.draw.drawBehind
import space.sovereigncast.hopp.resources.tags_hint
import space.sovereigncast.hopp.resources.tags_add_chip
import space.sovereigncast.hopp.resources.tag_edit

/** "Library" (ADR 0012): up next, downloads, the subscribed shows — since 0.4.0 up next and the
 *  downloads each a section that folds, as on Home, right there (Dennis, maintainer 2026-11-02:
 *  two taps to the list he cares most about). The shows are what the library is for and never
 *  fold (maintainer 2026-11-03). "Continue listening" moved to Home. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onShow: (Long) -> Unit,
    onQueue: () -> Unit,
    onDownloads: () -> Unit,
    /** A tag's own page: its shows, renamed, deleted (a long press on its chip). */
    onTag: (String) -> Unit = {},
    vm: LibraryViewModel = koinViewModel(),
) {
    val shows by vm.shows.collectAsState()
    val downloadCount by vm.downloadCount.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val counts by vm.counts.collectAsState()
    val asList by vm.asList.collectAsState()
    val sort by vm.sort.collectAsState()
    val queue by vm.queueItems.collectAsState()
    val downloaded by vm.downloaded.collectAsState()
    val collapsed by vm.collapsed.collectAsState()
    val playing = playlistNow()
    val nav = LocalEpisodeNav.current
    val tags by vm.tags.collectAsState()
    val tag by vm.tag.collectAsState()
    val showCount by vm.showCount.collectAsState()
    var tagging by remember { mutableStateOf<Long?>(null) }
    var creating by remember { mutableStateOf(false) }
    val allShows by vm.allShows.collectAsState()
    allShows.firstOrNull { it.id == tagging }?.let { s -> TagsSheet(s, tags, onTags = { vm.setTags(s.id, it) }) { tagging = null } }
    if (creating) NewTagSheet(allShows, onCreate = { name, ids -> vm.createTag(name, ids); creating = false }) { creating = false }
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
        // Empty only without any show — a tag that picks none still shows its row, to pick another.
        if (list.isEmpty() && showCount == 0) {
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
                // The playlist, as on Home (Dennis, maintainer 2026-11-03): "Now", then what comes next.
                val queueOpen = LIB_QUEUE !in collapsed
                val after = queue.filter { it.id != playing?.id }
                val inPlaylist = after.size + (if (playing != null) 1 else 0)
                item(key = "h-queue", span = full) {
                    SectionHeader(stringResource(Res.string.queue_title), queueOpen, inPlaylist, { vm.toggle(LIB_QUEUE) },
                        stringResource(Res.string.home_all, inPlaylist).takeIf { inPlaylist > 0 }, onQueue, modifier = Modifier.bleed(8.dp))
                }
                if (queueOpen) item(key = "queue", span = full) {
                    Box(Modifier.bleed(8.dp)) { PlaylistBody(playing, after, HomeViewModel.QUEUE_ROWS, onOpen = { nav.open(it) }, onReorder = vm::reorderQueue) }
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
                item(key = "h-shows", span = full) {
                    ShowsHeader(asList, vm::showAsList, sort, vm::sortBy, counts.values.sumOf { it.unheard }, { confirmAllPlayed = true }) {
                        // The listener's tags, always (maintainer 2026-11-03): a tap filters, "+ Tag" makes one, a long press edits.
                        TagRow(tags, tag.takeIf { t -> t == Settings.TAG_UNTAGGED || tags.any { it.equals(t, ignoreCase = true) } } ?: "", vm::filterTag, onTag) { creating = true }
                    }
                }
                items(list, key = { it.id }) { show ->
                    val c = counts[show.id]
                    // A long press: its tags, right here (maintainer 2026-11-03).
                    if (asList) ShowListRow(show, c, cover = if (large) 88.dp else 64.dp, onLongClick = { tagging = show.id }) { onShow(show.id) }
                    else ShowCard(show, c, large, onLongClick = { tagging = show.id }) { onShow(show.id) }
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
    asList: Boolean, onAsList: (Boolean) -> Unit, sort: String, onSort: (String) -> Unit, unheard: Int, onAllPlayed: () -> Unit,
    below: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val title = stringResource(Res.string.library_shows)
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp, bottom = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f).padding(vertical = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
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
        below()
        if (unheard > 0) Row(verticalAlignment = Alignment.CenterVertically) {
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
private fun ShowCard(show: Show, counts: ShowCounts?, large: Boolean, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    val corner = if (large) 18.dp else 16.dp
    // Round at the top like the cover, barely at the bottom: rounded there too, the
    // card's corner cut into the author's line (as it once did on Home's strip).
    Column(
        Modifier.padding(8.dp).clip(RoundedCornerShape(topStart = corner, topEnd = corner, bottomStart = 4.dp, bottomEnd = 4.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(bottom = 4.dp),
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
private fun ShowListRow(show: Show, counts: ShowCounts?, cover: Dp, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val sc: SovereigncastRepository = koinInject()
    val isSc by remember(show.id) { sc.layer(show.id).map { it != null } }.collectAsState(false)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(8.dp),
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

/**
 * The listener's tags as a row (maintainer 2026-11-03, as in AntennaPod): all, each tag, the shows without one.
 * A tap picks one — again, all; a long press on a tag opens its page.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TagRow(tags: List<String>, chosen: String, onPick: (String) -> Unit, onEdit: (String) -> Unit, onNew: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 6.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tags.isEmpty()) Text(stringResource(Res.string.tags_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            TagChip(stringResource(Res.string.tags_all), chosen.isEmpty(), onClick = { if (chosen.isNotEmpty()) onPick(chosen) })
            tags.forEach { t -> TagChip(t, chosen.equals(t, ignoreCase = true), onClick = { onPick(t) }, onLongClick = { onEdit(t) }) }
            TagChip(stringResource(Res.string.tags_untagged), chosen == Settings.TAG_UNTAGGED, onClick = { onPick(Settings.TAG_UNTAGGED) })
        }
        TagChip(stringResource(Res.string.tags_add_chip), false, dashed = true, onClick = onNew)
    }
    // The chosen tag's own page, without a long press (maintainer 2026-11-03).
    if (chosen.isNotEmpty() && chosen != Settings.TAG_UNTAGGED) Text(
        stringResource(Res.string.tag_edit, chosen) + " ›",
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { onEdit(chosen) }.padding(vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TagChip(label: String, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, dashed: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val outline = if (selected) colors.onSurface else colors.outlineVariant
    Text(
        label,
        Modifier.clip(shape)
            .then(if (dashed) Modifier.drawBehind {
                drawRoundRect(outline, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            } else Modifier.border(1.dp, outline, shape))
            .background(if (selected) colors.onSurface else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Tab)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = if (selected) colors.surface else colors.onSurface,
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
    )
}
