package space.sovereigncast.hopp.ui.library

import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import space.sovereigncast.hopp.resources.menu_more
import space.sovereigncast.hopp.resources.take_part_hidden_done
import space.sovereigncast.hopp.resources.take_part_undo
import space.sovereigncast.hopp.resources.take_part_hide_round
import space.sovereigncast.hopp.resources.take_part_hide_unlock
import space.sovereigncast.hopp.resources.take_part_hide_suggestions
import space.sovereigncast.hopp.resources.take_part_hide_show
import space.sovereigncast.hopp.resources.home_hello_night_name
import space.sovereigncast.hopp.resources.home_hello_evening_name
import space.sovereigncast.hopp.resources.home_hello_day_name
import space.sovereigncast.hopp.resources.home_hello_morning_name
import space.sovereigncast.hopp.resources.home_customize
import space.sovereigncast.hopp.resources.*
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.material3.TextButton
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import space.sovereigncast.hopp.ui.components.isoToEpochSeconds
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import space.sovereigncast.hopp.sovereigncast.ShowAction
import space.sovereigncast.hopp.ui.components.formatIsoShort
import space.sovereigncast.hopp.resources.favorites_title
import space.sovereigncast.hopp.resources.sc_b2u_label
import space.sovereigncast.hopp.resources.sc_rb_label
import space.sovereigncast.hopp.resources.sc_progress
import space.sovereigncast.hopp.resources.sc_publish_at
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.HoppSymbol
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.home_all
import space.sovereigncast.hopp.resources.home_boosted
import space.sovereigncast.hopp.resources.home_recent
import space.sovereigncast.hopp.resources.inbox_dismiss
import space.sovereigncast.hopp.resources.inbox_empty_title
import space.sovereigncast.hopp.resources.inbox_no_shows
import space.sovereigncast.hopp.resources.inbox_to_discover
import space.sovereigncast.hopp.resources.library_continue
import space.sovereigncast.hopp.resources.nav_new
import space.sovereigncast.hopp.ui.components.formatSats
import space.sovereigncast.hopp.ui.theme.Hopp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight

/**
 * Home (device feedback 2026-10-08, replaces the New tab of ADR 0012): the
 * newest of "New" with a way to all of it, continue listening, recently
 * played, recently boosted. Nostr comments join with the identity of phase 2.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    onInbox: () -> Unit, onDiscover: () -> Unit, onShow: (Long) -> Unit = {}, onFavorites: () -> Unit = {},
    onLibrary: () -> Unit = {}, onCommunity: (Long) -> Unit = onShow,
    onAction: (ShowAction) -> Unit = { onShow(it.podcastId) },
    snackbar: SnackbarHostState? = null,
    onSyncSettings: () -> Unit = {},
    onQueue: () -> Unit = {},
    vm: HomeViewModel = koinViewModel(),
) {
    val nav = LocalEpisodeNav.current
    val inbox by vm.inbox.collectAsState()
    val hasShows by vm.hasShows.collectAsState()
    val inProgress by vm.inProgress.collectAsState()
    val played by vm.recentlyPlayed.collectAsState()
    val boosted by vm.recentlyBoosted.collectAsState()
    val actions by vm.actions.collectAsState()
    val takePart by vm.takePart.collectAsState()
    val hiddenText = stringResource(Res.string.take_part_hidden_done)
    val undoText = stringResource(Res.string.take_part_undo)
    val scope = rememberCoroutineScope()
    // Hidden under "Take part", with the way back at once (device feedback 2026-10-22).
    val hide = { key: String ->
        vm.hideTakePart(key)
        scope.launch {
            if (snackbar?.showSnackbar(hiddenText, actionLabel = undoText, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed)
                vm.showTakePartAgain(key)
        }
        Unit
    }
    val favorites by vm.favorites.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val shows by vm.shows.collectAsState()
    val rhythms by vm.rhythms.collectAsState()
    val lastChecked by vm.lastChecked.collectAsState()
    // Greeting, "updated … ago" and countdowns move with the clock.
    val now by produceState(nowSeconds()) { while (true) { delay(30_000); value = nowSeconds() } }
    val continueTitle = stringResource(Res.string.library_continue)
    val recentTitle = stringResource(Res.string.home_recent)

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh) {
        val newest = inbox ?: return@PullToRefreshBox
        if (hasShows == false) {
            EmptyState(stringResource(Res.string.inbox_empty_title), stringResource(Res.string.inbox_no_shows)) {
                Button(onClick = onDiscover) { Text(stringResource(Res.string.inbox_to_discover)) }
            }
            return@PullToRefreshBox
        }
        val collapsed by vm.collapsed.collectAsState()
        val savedOrder by vm.savedOrder.collectAsState()
        val hidden by vm.hidden.collectAsState()
        val greetName by vm.greetName.collectAsState()
        val syncStatus by vm.syncStatus.collectAsState()
        var syncOpen by remember { mutableStateOf(false) }
        syncStatus?.takeIf { syncOpen }?.let { st ->
            SyncSheet(st, now * 1000, onSyncNow = vm::syncNow, onSettings = { syncOpen = false; onSyncSettings() }, onRewrite = vm::rewrite) { syncOpen = false }
        }
        var allActions by remember { mutableStateOf(false) }
        val queue by vm.queue.collectAsState()
        // Home arranged in place (Dennis, maintainer 2026-11-02): the tune button or a long press on a title.
        var editing by rememberSaveable { mutableStateOf(false) }
        val current by vm.current.collectAsState()
        if (editing) current?.let { s ->
            androidx.compose.ui.backhandler.BackHandler { editing = false }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.home_customize), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { editing = false }) { Text(stringResource(Res.string.home_edit_done)) }
                }
                Box(Modifier.weight(1f)) {
                    HomeSectionsEditor(s, shows.mapNotNull { sh -> sh.podcastGuid?.takeIf { it.isNotEmpty() }?.let { it to sh.title } }.toMap(),
                        note = stringResource(Res.string.home_edit_note)) { change -> vm.update(change) }
                }
            }
            return@PullToRefreshBox
        }
        val edit = { editing = true }
        // The sections in the listener's order, into a list — one, or each of two columns. [wide]: "Your shows" as a grid.
        val sections: LazyListScope.(List<String>, Boolean) -> Unit = { keys, wide ->
            keys.forEach { key ->
                when (key) {
                    HomeSections.NEW -> {
                    if (newest.isNotEmpty()) {
                        val open = "new" !in collapsed
                        item(key = "h-new") {
                            SectionHeader(stringResource(Res.string.nav_new), open, newest.size, { vm.toggle("new") }, stringResource(Res.string.home_all, newest.size), onInbox, edit)
                        }
                        if (open) items(newest.take(HomeViewModel.HOME_ROWS), key = { "n${it.id}" }) { item ->
                            EpisodeRow(item, showPodcast = true, onClick = { nav.open(item) }, modifier = Modifier.animateItem()) {
                                PlayButton(item)
                                EpisodeMenu(item)
                            }
                        }
                    }
                    }
                    HomeSections.CONTINUE -> section("continue", continueTitle, inProgress, collapsed, vm::toggle, nav, edit)
                    HomeSections.QUEUE -> {
                    // What comes next, in its order, arranged right here (Dennis, maintainer 2026-11-02).
                    if (queue.isNotEmpty()) {
                        val open = "queue" !in collapsed
                        item(key = "h-queue") {
                            SectionHeader(stringResource(Res.string.queue_title), open, queue.size, { vm.toggle("queue") }, stringResource(Res.string.home_all, queue.size), onQueue, edit)
                        }
                        if (open) item(key = "queue") { QueuePreview(queue, HomeViewModel.QUEUE_ROWS, onOpen = { nav.open(it) }, onReorder = vm::reorderQueue) }
                    }
                    }
                    HomeSections.YOUR_SHOWS -> {
                    // The shows themselves, when each comes out next — Release Boosting's countdown first.
                    if (shows.isNotEmpty()) {
                        val open = "yourshows" !in collapsed
                        item(key = "h-yourshows") {
                            SectionHeader(stringResource(Res.string.home_your_shows), open, shows.size, { vm.toggle("yourshows") }, stringResource(Res.string.home_all, shows.size), onLibrary, edit)
                        }
                        if (open) item(key = "yourshows") { ShowsStrip(shows, rhythms, actions, now, onShow, grid = wide) }
                    }
                    }
                    HomeSections.TAKE_PART -> {
                    // Taking part at Sovereigncast shows: Release Boosting, Boost2Unlock and open
                    // suggestions — only when there is something to do, two lines unless asked.
                    if (takePart.isNotEmpty()) {
                        val open = "shows" !in collapsed
                        item(key = "h-shows") { SectionHeader(stringResource(Res.string.home_take_part), open, takePart.size, { vm.toggle("shows") }, onLongPress = edit) }
                        if (open) {
                            val shown = if (allActions) takePart else takePart.take(HomeViewModel.TAKE_PART_ROWS)
                            // By index: two episodes of a show can share a title, and a repeated key crashes (code review 2026-10-17, H7).
                            itemsIndexed(shown, key = { i, _ -> "a$i" }) { _, a ->
                                // "Vote" leads to the votes, not to the episodes (device feedback 2026-10-12);
                                // a round or an unlock straight to its sheet on the show (device feedback 2026-10-22).
                                if (a.kind == ShowAction.Kind.Suggestions) SuggestionsRow(a, hide, Modifier.animateItem()) { onCommunity(a.podcastId) }
                                else ShowActionRow(a, hide, Modifier.animateItem()) { onAction(a) }
                            }
                            if (takePart.size > shown.size) item(key = "a-more") {
                                TextButton(onClick = { allActions = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                    Text(stringResource(Res.string.home_show_all, takePart.size))
                                }
                            }
                        }
                    }
                    }
                    HomeSections.FAVORITES -> {
                    // Favourites (device feedback 2026-10-11): only once there are any.
                    if (favorites.isNotEmpty()) {
                        val open = "favorites" !in collapsed
                        item(key = "h-favorites") {
                            SectionHeader(stringResource(Res.string.favorites_title), open, favorites.size, { vm.toggle("favorites") },
                                stringResource(Res.string.home_all, favorites.size), onFavorites, edit)
                        }
                        if (open) items(favorites.take(HomeViewModel.HOME_ROWS), key = { "f${it.id}" }) { item ->
                            EpisodeRow(item, showPodcast = true, onClick = { nav.open(item) }, modifier = Modifier.animateItem()) { PlayButton(item); EpisodeMenu(item) }
                        }
                    }
                    }
                    HomeSections.BOOSTED -> {
                    if (boosted.isNotEmpty()) {
                        val open = "boosted" !in collapsed
                        item(key = "h-boosted") { SectionHeader(stringResource(Res.string.home_boosted), open, boosted.size, { vm.toggle("boosted") }, onLongPress = edit) }
                        if (open) items(boosted, key = { "b${it.first.id}" }) { (item, sats) ->
                            EpisodeRow(item, showPodcast = true, onClick = { nav.open(item) }, modifier = Modifier.animateItem()) {
                                Text("${formatSats(sats)} sats", color = Hopp.colors.valueText, fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    }
                    // Last by default: what was heard is the least likely next step (device feedback 2026-10-10).
                    HomeSections.RECENT -> section("recent", recentTitle, played, collapsed, vm::toggle, nav, edit)
                }
            }
        }
        // The listener's order, or Hopp's, without what they hid (device feedback 2026-10-13).
        val order = HomeSections.order(savedOrder).filter { it !in hidden }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // A greeting, and what the last refresh brought (device feedback 2026-10-12): never an empty page while there are shows.
            if (maxWidth < HomeSections.TWO_COLUMNS) LazyColumn {
                item(key = "h-hello") { Greeting(newest.size, lastChecked, now, greetName, edit, syncStatus) { syncOpen = true } }
                sections(order, false)
            } else Column(Modifier.fillMaxSize()) {
                // A large window (maintainer 2026-11-03): listening on the left, the shows on the right, the greeting over both.
                Greeting(newest.size, lastChecked, now, greetName, edit, syncStatus) { syncOpen = true }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    LazyColumn(Modifier.weight(1.35f).fillMaxHeight()) { sections(order.filter { it in HomeSections.listening }, true) }
                    LazyColumn(Modifier.weight(1f).fillMaxHeight()) { sections(order.filter { it !in HomeSections.listening }, true) }
                }
            }
        }
    }
}

private fun LazyListScope.section(key: String, title: String, list: List<EpisodeItem>, collapsed: Set<String>, toggle: (String) -> Unit, nav: EpisodeNav, edit: () -> Unit) {
    if (list.isEmpty()) return
    val open = key !in collapsed
    item(key = "h-$key") { SectionHeader(title, open, list.size, { toggle(key) }, onLongPress = edit) }
    if (open) items(list, key = { key + it.id }) { item ->
        EpisodeRow(item, showPodcast = true, onClick = { nav.open(item) }, modifier = Modifier.animateItem()) { PlayButton(item); EpisodeMenu(item) }
    }
}

/** A section's title folds it away and back (device feedback 2026-10-09);
 *  folded, it says how many it holds. A long press arranges the page ([onLongPress]). */
@Composable
internal fun SectionHeader(
    title: String, open: Boolean, count: Int, onToggle: () -> Unit, action: String? = null, onAction: () -> Unit = {},
    onLongPress: (() -> Unit)? = null, modifier: Modifier = Modifier,
) {
    val turn by animateFloatAsState(if (open) 0f else -90f)
    Row(
        modifier.fillMaxWidth().combinedClickable(onClickLabel = title, onClick = onToggle, onLongClick = onLongPress)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp).semantics { stateDescription = if (open) "open" else "closed" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Icon(HoppIcons.ChevronDown, null, Modifier.padding(start = 4.dp).size(22.dp).rotate(turn), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!open) Text(" $count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (action != null && open) Text(
            "$action ›", Modifier.clickable(onClick = onAction).padding(8.dp),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        )
    }
}

/** One running action at a show: its cover, what and where, how far — a tap opens its sheet on the show;
 *  ⋮ or a long press hides it, its show's suggestions or the whole show from "Take part". */
@Composable
private fun ShowActionRow(a: ShowAction, onHide: (String) -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true }).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(a.showImage, 44.dp, corner = 10.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                stringResource(if (a.kind == ShowAction.Kind.ReleaseBoosting) Res.string.sc_rb_label else Res.string.sc_b2u_label) + " · " + TakePart.shortTitle(a.showTitle),
                style = MaterialTheme.typography.labelSmall, color = Hopp.colors.valueText, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(a.episodeTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(
                progress = { if (a.goalSats > 0) (a.raisedSats.toFloat() / a.goalSats).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Hopp.colors.value, trackColor = Hopp.colors.progressTrack, drawStopIndicator = {},
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // At its goal: a tick, and nothing more to boost (maintainer, 2026-10-23).
                if (a.reached) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Hopp.colors.ok, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    (if (a.reached) stringResource(Res.string.sc_goal_reached) else stringResource(Res.string.sc_progress, formatSats(a.raisedSats), formatSats(a.goalSats))) +
                        (a.publishAt?.let { " · " + stringResource(Res.string.sc_publish_at, formatIsoShort(it)) } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TakePartMenu(a, menu, { menu = it }, onHide)
    }
}

/** ⋮ under "Take part": this round or episode, the show's suggestions, or the whole show. */
@Composable
private fun TakePartMenu(a: ShowAction, open: Boolean, onOpen: (Boolean) -> Unit, onHide: (String) -> Unit) {
    if (a.podcastGuid.isEmpty()) { Spacer(Modifier.width(8.dp)); return }
    Box {
        IconButton(onClick = { onOpen(true) }) {
            Icon(Icons.Rounded.MoreVert, stringResource(Res.string.menu_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { onOpen(false) }) {
            fun pick(key: String) { onOpen(false); onHide(key) }
            val title = TakePart.shortTitle(a.showTitle)
            // Suggestions are offered only on a suggestion (device feedback 2026-10-23).
            TakePart.itself(a)?.let { key ->
                val label = when (a.kind) {
                    ShowAction.Kind.ReleaseBoosting -> stringResource(Res.string.take_part_hide_round)
                    ShowAction.Kind.Boost2Unlock -> stringResource(Res.string.take_part_hide_unlock)
                    ShowAction.Kind.Suggestions -> stringResource(Res.string.take_part_hide_suggestions, title)
                }
                DropdownMenuItem(text = { Text(label) }, onClick = { pick(key) })
            }
            DropdownMenuItem(text = { Text(stringResource(Res.string.take_part_hide_show, title)) }, onClick = { pick(TakePart.show(a.podcastGuid)) })
        }
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun nowSeconds(): Long = kotlin.time.Clock.System.now().epochSeconds

/** "Good evening" and what the last refresh brought — Home's first line (device feedback 2026-10-12) —
 *  with "Customize Home" as a quiet icon at its end (device feedback 2026-10-17: at the bottom
 *  alone it was out of sight below six sections). */
@OptIn(kotlin.time.ExperimentalTime::class)
@Composable
private fun Greeting(newCount: Int, lastChecked: Long?, now: Long, name: String? = null, onCustomize: (() -> Unit)? = null,
                     sync: space.sovereigncast.hopp.sync.SyncStatus? = null, onSync: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val hour = kotlin.time.Instant.fromEpochSeconds(now).toLocalDateTime(TimeZone.currentSystemDefault()).hour
    // By name when the listener gave one for their boosts (device feedback 2026-10-13).
    val hello = if (name == null) stringResource(when (hour) {
        in 5..10 -> Res.string.home_hello_morning
        in 11..17 -> Res.string.home_hello_day
        in 18..22 -> Res.string.home_hello_evening
        else -> Res.string.home_hello_night
    }) else stringResource(when (hour) {
        in 5..10 -> Res.string.home_hello_morning_name
        in 11..17 -> Res.string.home_hello_day_name
        in 18..22 -> Res.string.home_hello_evening_name
        else -> Res.string.home_hello_night_name
    }, name)
    val news = if (newCount > 0) stringResource(Res.string.home_new_count, newCount) else stringResource(Res.string.home_nothing_new)
    val updated = lastChecked?.let { stringResource(Res.string.home_updated, ago(now - it)) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The logo, not the hop alone — that means boost (ADR 0019).
        HoppSymbol(Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            FittingLine(hello, MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            // One line, the sync mark at its end, always seen (maintainer 2026-10-22: a wrap looked poor).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(news, updated).joinToString(" · "), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sync != null && (sync.running || sync.lastSyncedMs != null || sync.problem != null)) SyncMark(sync, onSync)
            }
        }
        if (onCustomize != null) IconButton(onClick = onCustomize, modifier = Modifier.offset(x = 8.dp)) {
            Icon(HoppIcons.Tune, stringResource(Res.string.home_customize), Modifier.size(22.dp), tint = colors.onSurfaceVariant)
        }
    }
}

/**
 * One line, smaller where it must (device feedback 2026-10-17: "Guten Morgen,
 * Thorsten" ran off the screen) — a second line would take too much of Home.
 * Measured here, not with Text's autoSize, which did not shrink it on iOS:
 * the largest size from [style]'s down to [min] that fits, then an ellipsis.
 */
@Composable
internal fun FittingLine(text: String, style: TextStyle, min: TextUnit = 16.sp) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val width = constraints.maxWidth
        val size = remember(text, style, width) {
            var sp = style.fontSize.value
            while (sp > min.value && measurer.measure(text, style.copy(fontSize = sp.sp), maxLines = 1, softWrap = false).size.width > width) sp -= 1f
            sp.sp
        }
        Text(text, style = style.copy(fontSize = size), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ago(seconds: Long): String = when {
    seconds < 60 -> stringResource(Res.string.home_just_now)
    seconds < 3_600 -> stringResource(Res.string.home_minutes_ago, (seconds / 60).toInt())
    seconds < 86_400 -> stringResource(Res.string.home_hours_ago, (seconds / 3_600).toInt())
    else -> stringResource(Res.string.home_days_ago, (seconds / 86_400).toInt())
}

/** Until a Release Boosting episode comes out, short enough for under a cover. */
@Composable
private fun countdown(seconds: Long): String = when {
    seconds <= 60 -> stringResource(Res.string.home_soon)
    seconds < 3_600 -> stringResource(Res.string.home_in_minutes, (seconds / 60).toInt())
    seconds < 86_400 -> stringResource(Res.string.home_in_hours, (seconds / 3_600).toInt())
    else -> stringResource(Res.string.home_in_days, (seconds / 86_400).toInt(), ((seconds % 86_400) / 3_600).toInt())
}

@Composable
private fun rhythmText(r: space.sovereigncast.hopp.data.Rhythm): String = when (r) {
    space.sovereigncast.hopp.data.Rhythm.Daily -> stringResource(Res.string.home_daily)
    space.sovereigncast.hopp.data.Rhythm.SeveralWeekly -> stringResource(Res.string.home_several_weekly)
    is space.sovereigncast.hopp.data.Rhythm.Weekly -> r.weekday?.let { stringResource(Res.string.home_usually, stringResource(WEEKDAYS[it - 1])) }
        ?: stringResource(Res.string.home_weekly)
    space.sovereigncast.hopp.data.Rhythm.Biweekly -> stringResource(Res.string.home_biweekly)
    space.sovereigncast.hopp.data.Rhythm.Monthly -> stringResource(Res.string.home_monthly)
}

/** Wide enough for "every 2 wks" and a countdown under the cover … */
private val STRIP_TILE = 92.dp
/** … and larger where the page has room (a tablet, the browser): 92 dp looked lost there (UX review 2026-10-30). */
private val STRIP_TILE_WIDE = 128.dp
private val STRIP_TILE_SHAPE = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 4.dp, bottomEnd = 4.dp)

private val WEEKDAYS = listOf(Res.string.wd_1, Res.string.wd_2, Res.string.wd_3, Res.string.wd_4, Res.string.wd_5, Res.string.wd_6, Res.string.wd_7)

/**
 * The subscribed shows as covers (device feedback 2026-10-12): those with a
 * Release Boosting round first, its countdown in orange; the others with
 * when they usually come out. A tap opens the show.
 */
@Composable
private fun ShowsStrip(
    shows: List<space.sovereigncast.hopp.data.Show>, rhythms: Map<Long, space.sovereigncast.hopp.data.Rhythm>,
    actions: List<ShowAction>, now: Long, onShow: (Long) -> Unit,
    /** A grid of the first [GRID_SHOWS] instead of a strip — in a column of a large window (maintainer 2026-11-03). */
    grid: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val releases = actions.filter { it.kind == ShowAction.Kind.ReleaseBoosting }
        .mapNotNull { a -> a.publishAt?.let { isoToEpochSeconds(it) }?.let { a.podcastId to it } }.toMap()
    val ordered = shows.sortedBy { if (it.id in releases) 0 else 1 }
    // A show with a round moves to the front once Sovereigncast answers — after
    // the strip is shown. A lazy row keeps its first item in view, so the new
    // first one sat off the left edge (device feedback 2026-10-16): back to the
    // start, unless the listener has scrolled on.
    if (grid) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            val columns = (maxWidth / 112.dp).toInt().coerceIn(3, 6)
            val tile = (maxWidth - 12.dp * (columns - 1)) / columns
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = columns) {
                ordered.take(columns * 2).forEach { show ->
                    Column(Modifier.width(tile).clip(STRIP_TILE_SHAPE).clickable(onClickLabel = show.title) { onShow(show.id) }.padding(bottom = 4.dp)) {
                        Cover(show.imageUrl, tile, corner = 14.dp)
                        Text(show.title, Modifier.padding(top = 5.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        return
    }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val first = ordered.firstOrNull()?.id
    androidx.compose.runtime.LaunchedEffect(first) { if (state.firstVisibleItemIndex <= 1) state.scrollToItem(0) }
    androidx.compose.foundation.layout.BoxWithConstraints {
    val tile = if (maxWidth >= space.sovereigncast.hopp.ui.components.MEDIUM_WIDTH) STRIP_TILE_WIDE else STRIP_TILE
    space.sovereigncast.hopp.ui.components.SideScroll(state) {
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(ordered, key = { "s${it.id}" }) { show ->
            // Only the top corners echo the cover's: rounded at the bottom too, the
            // tile cut the ends off the rhythm line under it.
            Column(
                Modifier.width(tile).clip(STRIP_TILE_SHAPE)
                    .clickable(onClickLabel = show.title) { onShow(show.id) }.padding(bottom = 4.dp),
            ) {
                Cover(show.imageUrl, tile, corner = 14.dp)
                Text(show.title, Modifier.padding(top = 5.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val release = releases[show.id]
                if (release != null) Row(
                    Modifier.padding(top = 2.dp).clip(RoundedCornerShape(50)).background(Hopp.colors.value).padding(horizontal = 6.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(HoppIcons.Hop, null, Modifier.size(10.dp), tint = Hopp.colors.onValue)
                    Spacer(Modifier.size(3.dp))
                    Text(countdown(release - now), style = MaterialTheme.typography.labelSmall, color = Hopp.colors.onValue, fontWeight = FontWeight.Bold, maxLines = 1)
                } else rhythms[show.id]?.let { r ->
                    Text(rhythmText(r), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    }
    }
}

/** Open suggestions at a show, waiting for this listener's vote — one slim line. */
@Composable
private fun SuggestionsRow(a: ShowAction, onHide: (String) -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true }).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(a.showImage, 44.dp, corner = 10.dp)
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.home_suggestions_label) + " · " + TakePart.shortTitle(a.showTitle), style = MaterialTheme.typography.labelSmall,
                color = Hopp.colors.valueText, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(Res.string.home_suggestions_waiting, a.waiting), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(stringResource(Res.string.home_vote) + " ›", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        TakePartMenu(a, menu, { menu = it }, onHide)
    }
}

