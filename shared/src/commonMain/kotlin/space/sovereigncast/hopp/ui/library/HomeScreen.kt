package space.sovereigncast.hopp.ui.library

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

/**
 * Home (device feedback 2026-10-08, replaces the New tab of ADR 0012): the
 * newest of "New" with a way to all of it, continue listening, recently
 * played, recently boosted. Nostr comments join with the identity of phase 2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onInbox: () -> Unit, onDiscover: () -> Unit, onShow: (Long) -> Unit = {}, onFavorites: () -> Unit = {},
    onLibrary: () -> Unit = {}, onUnheard: () -> Unit = {}, onCommunity: (Long) -> Unit = onShow, vm: HomeViewModel = koinViewModel(),
) {
    val nav = LocalEpisodeNav.current
    val inbox by vm.inbox.collectAsState()
    val hasShows by vm.hasShows.collectAsState()
    val inProgress by vm.inProgress.collectAsState()
    val played by vm.recentlyPlayed.collectAsState()
    val boosted by vm.recentlyBoosted.collectAsState()
    val actions by vm.actions.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val shows by vm.shows.collectAsState()
    val rhythms by vm.rhythms.collectAsState()
    val unheard by vm.unheard.collectAsState()
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
        var allActions by remember { mutableStateOf(false) }
        LazyColumn {
            // A greeting, and what the last refresh brought (device feedback 2026-10-12):
            // never an empty page while there are shows.
            item(key = "h-hello") { Greeting(newest.size, lastChecked, now) }
            if (newest.isNotEmpty()) {
                val open = "new" !in collapsed
                item(key = "h-new") {
                    SectionHeader(stringResource(Res.string.nav_new), open, newest.size, { vm.toggle("new") }, stringResource(Res.string.home_all, newest.size), onInbox)
                }
                if (open) items(newest.take(HomeViewModel.HOME_ROWS), key = { "n${it.id}" }) { item ->
                    EpisodeRow(item, showPodcast = true, onClick = { nav.open(item) }, modifier = Modifier.animateItem()) {
                        PlayButton(item)
                        EpisodeMenu(item)
                    }
                }
            }
            section("continue", continueTitle, inProgress, collapsed, vm::toggle, nav)
            // The shows themselves, when each comes out next — Release Boosting's countdown first.
            if (shows.isNotEmpty()) {
                val open = "yourshows" !in collapsed
                item(key = "h-yourshows") {
                    SectionHeader(stringResource(Res.string.home_your_shows), open, shows.size, { vm.toggle("yourshows") }, stringResource(Res.string.home_all, shows.size), onLibrary)
                }
                if (open) item(key = "yourshows") { ShowsStrip(shows, rhythms, actions, now, onShow) }
            }
            // Taking part at Sovereigncast shows: Release Boosting, Boost2Unlock and open
            // suggestions — only when there is something to do, two lines unless asked.
            if (actions.isNotEmpty()) {
                val open = "shows" !in collapsed
                item(key = "h-shows") { SectionHeader(stringResource(Res.string.home_take_part), open, actions.size, { vm.toggle("shows") }) }
                if (open) {
                    val shown = if (allActions) actions else actions.take(HomeViewModel.TAKE_PART_ROWS)
                    items(shown, key = { "a${it.podcastId}${it.kind}${it.episodeTitle}" }) { a ->
                        // "Vote" leads to the votes, not to the episodes (device feedback 2026-10-12).
                        if (a.kind == ShowAction.Kind.Suggestions) SuggestionsRow(a, Modifier.animateItem()) { onCommunity(a.podcastId) }
                        else ShowActionRow(a, Modifier.animateItem()) { onShow(a.podcastId) }
                    }
                    if (actions.size > shown.size) item(key = "a-more") {
                        TextButton(onClick = { allActions = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(Res.string.home_show_all, actions.size))
                        }
                    }
                }
            }
            // On a quiet day the unheard of each show come before the rest; with news, after it.
            if (newest.isEmpty()) fromShows(unheard, collapsed, vm::toggle, nav, onUnheard)
            // Favourites (device feedback 2026-10-11): only once there are any.
            if (favorites.isNotEmpty()) {
                val open = "favorites" !in collapsed
                item(key = "h-favorites") {
                    SectionHeader(stringResource(Res.string.favorites_title), open, favorites.size, { vm.toggle("favorites") },
                        stringResource(Res.string.home_all, favorites.size), onFavorites)
                }
                if (open) items(favorites.take(HomeViewModel.HOME_ROWS), key = { "f${it.id}" }) { item ->
                    EpisodeRow(item, showPodcast = true, onClick = { nav.open(item) }, modifier = Modifier.animateItem()) { PlayButton(item); EpisodeMenu(item) }
                }
            }
            if (boosted.isNotEmpty()) {
                val open = "boosted" !in collapsed
                item(key = "h-boosted") { SectionHeader(stringResource(Res.string.home_boosted), open, boosted.size, { vm.toggle("boosted") }) }
                if (open) items(boosted, key = { "b${it.first.id}" }) { (item, sats) ->
                    EpisodeRow(item, showPodcast = true, onClick = { nav.open(item) }, modifier = Modifier.animateItem()) {
                        Text("${formatSats(sats)} sats", color = Hopp.colors.valueText, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (newest.isNotEmpty()) fromShows(unheard, collapsed, vm::toggle, nav, onUnheard)
            // Last: what was heard is the least likely next step (device feedback 2026-10-10).
            section("recent", recentTitle, played, collapsed, vm::toggle, nav)
        }
    }
}

private fun LazyListScope.section(key: String, title: String, list: List<EpisodeItem>, collapsed: Set<String>, toggle: (String) -> Unit, nav: EpisodeNav) {
    if (list.isEmpty()) return
    val open = key !in collapsed
    item(key = "h-$key") { SectionHeader(title, open, list.size, { toggle(key) }) }
    if (open) items(list, key = { key + it.id }) { item ->
        EpisodeRow(item, showPodcast = true, onClick = { nav.open(item) }, modifier = Modifier.animateItem()) { PlayButton(item); EpisodeMenu(item) }
    }
}

/** A section's title folds it away and back (device feedback 2026-10-09);
 *  folded, it says how many it holds. */
@Composable
private fun SectionHeader(title: String, open: Boolean, count: Int, onToggle: () -> Unit, action: String? = null, onAction: () -> Unit = {}) {
    val turn by animateFloatAsState(if (open) 0f else -90f)
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = title, onClick = onToggle)
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

/** One running action at a show: its cover, what and where, how far — a tap opens the show. */
@Composable
private fun ShowActionRow(a: ShowAction, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(a.showImage, 44.dp, corner = 10.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                stringResource(if (a.kind == ShowAction.Kind.ReleaseBoosting) Res.string.sc_rb_label else Res.string.sc_b2u_label) + " · " + a.showTitle,
                style = MaterialTheme.typography.labelSmall, color = Hopp.colors.valueText, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(a.episodeTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(
                progress = { if (a.goalSats > 0) (a.raisedSats.toFloat() / a.goalSats).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Hopp.colors.value, trackColor = Hopp.colors.progressTrack, drawStopIndicator = {},
            )
            Text(
                stringResource(Res.string.sc_progress, formatSats(a.raisedSats), formatSats(a.goalSats)) +
                    (a.publishAt?.let { " · " + stringResource(Res.string.sc_publish_at, formatIsoShort(it)) } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun nowSeconds(): Long = kotlin.time.Clock.System.now().epochSeconds

/** "Good evening" and what the last refresh brought — Home's first line (device feedback 2026-10-12). */
@OptIn(kotlin.time.ExperimentalTime::class)
@Composable
private fun Greeting(newCount: Int, lastChecked: Long?, now: Long) {
    val colors = MaterialTheme.colorScheme
    val hour = kotlin.time.Instant.fromEpochSeconds(now).toLocalDateTime(TimeZone.currentSystemDefault()).hour
    val hello = stringResource(when (hour) {
        in 5..10 -> Res.string.home_hello_morning
        in 11..17 -> Res.string.home_hello_day
        in 18..22 -> Res.string.home_hello_evening
        else -> Res.string.home_hello_night
    })
    val news = if (newCount > 0) stringResource(Res.string.home_new_count, newCount) else stringResource(Res.string.home_nothing_new)
    val updated = lastChecked?.let { stringResource(Res.string.home_updated, ago(now - it)) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The logo, not the hop alone — that means boost (ADR 0019).
        HoppSymbol(Modifier.size(48.dp))
        Column {
            Text(hello, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(listOfNotNull(news, updated).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun ago(seconds: Long): String = when {
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

/** Wide enough for "every 2 wks" and a countdown under the cover. */
private val STRIP_TILE = 92.dp
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
) {
    val colors = MaterialTheme.colorScheme
    val releases = actions.filter { it.kind == ShowAction.Kind.ReleaseBoosting }
        .mapNotNull { a -> a.publishAt?.let { isoToEpochSeconds(it) }?.let { a.podcastId to it } }.toMap()
    val ordered = shows.sortedBy { if (it.id in releases) 0 else 1 }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(ordered, key = { "s${it.id}" }) { show ->
            // Only the top corners echo the cover's: rounded at the bottom too, the
            // tile cut the ends off the rhythm line under it.
            Column(
                Modifier.width(STRIP_TILE).clip(STRIP_TILE_SHAPE)
                    .clickable(onClickLabel = show.title) { onShow(show.id) }.padding(bottom = 4.dp),
            ) {
                Cover(show.imageUrl, STRIP_TILE, corner = 14.dp)
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

/** Open suggestions at a show, waiting for this listener's vote — one slim line. */
@Composable
private fun SuggestionsRow(a: ShowAction, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(a.showImage, 44.dp, corner = 10.dp)
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.home_suggestions_label) + " · " + a.showTitle, style = MaterialTheme.typography.labelSmall,
                color = Hopp.colors.valueText, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(Res.string.home_suggestions_waiting, a.waiting), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(stringResource(Res.string.home_vote) + " ›", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

/** "From your shows": per show the newest episode not heard yet, three of them. */
private fun LazyListScope.fromShows(list: List<EpisodeItem>, collapsed: Set<String>, toggle: (String) -> Unit, nav: EpisodeNav, onMore: () -> Unit) {
    if (list.isEmpty()) return
    val open = "fromshows" !in collapsed
    item(key = "h-fromshows") {
        Column {
            SectionHeader(stringResource(Res.string.home_from_shows), open, list.size, { toggle("fromshows") },
                stringResource(Res.string.home_more).takeIf { list.size > HomeViewModel.QUIET_ROWS }, onMore)
            if (open) Text(stringResource(Res.string.home_unheard), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (open) items(list.take(HomeViewModel.QUIET_ROWS), key = { "u${it.id}" }) { item ->
        EpisodeRow(item, showPodcast = true, onClick = { nav.open(item) }, modifier = Modifier.animateItem()) { PlayButton(item); EpisodeMenu(item) }
    }
}
