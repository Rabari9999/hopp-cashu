package space.sovereigncast.hopp.ui.library

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
import space.sovereigncast.hopp.resources.home_at_your_shows
import space.sovereigncast.hopp.resources.favorites_title
import space.sovereigncast.hopp.resources.sc_b2u_label
import space.sovereigncast.hopp.resources.sc_rb_label
import space.sovereigncast.hopp.resources.sc_progress
import space.sovereigncast.hopp.resources.sc_publish_at
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import space.sovereigncast.hopp.ui.components.HoppIcons
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
import space.sovereigncast.hopp.resources.inbox_empty_text
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
fun HomeScreen(onInbox: () -> Unit, onDiscover: () -> Unit, onShow: (Long) -> Unit = {}, onFavorites: () -> Unit = {}, vm: HomeViewModel = koinViewModel()) {
    val nav = LocalEpisodeNav.current
    val inbox by vm.inbox.collectAsState()
    val hasShows by vm.hasShows.collectAsState()
    val inProgress by vm.inProgress.collectAsState()
    val played by vm.recentlyPlayed.collectAsState()
    val boosted by vm.recentlyBoosted.collectAsState()
    val actions by vm.actions.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
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
        if (newest.isEmpty() && inProgress.isEmpty() && played.isEmpty() && boosted.isEmpty() && actions.isEmpty() && favorites.isEmpty()) {
            EmptyState(stringResource(Res.string.inbox_empty_title), stringResource(Res.string.inbox_empty_text))
            return@PullToRefreshBox
        }
        val collapsed by vm.collapsed.collectAsState()
        LazyColumn {
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
            // Release Boosting and Boost2Unlock at the listener's shows — quiet,
            // one line each, the details on the show page (device feedback 2026-10-10).
            if (actions.isNotEmpty()) {
                val open = "shows" !in collapsed
                item(key = "h-shows") { SectionHeader(stringResource(Res.string.home_at_your_shows), open, actions.size, { vm.toggle("shows") }) }
                if (open) items(actions, key = { "a${it.podcastId}${it.kind}${it.episodeTitle}" }) { a ->
                    ShowActionRow(a, Modifier.animateItem()) { onShow(a.podcastId) }
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
