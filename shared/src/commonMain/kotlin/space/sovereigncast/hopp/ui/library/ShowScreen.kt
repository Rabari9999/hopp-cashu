package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import space.sovereigncast.hopp.resources.show_support
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.show_copy_feed
import space.sovereigncast.hopp.resources.share_title
import space.sovereigncast.hopp.resources.cancel
import space.sovereigncast.hopp.resources.months
import space.sovereigncast.hopp.resources.sc_alltime
import space.sovereigncast.hopp.resources.sc_alltime_note
import space.sovereigncast.hopp.resources.sc_no_boosts
import space.sovereigncast.hopp.resources.sc_no_suggestions
import space.sovereigncast.hopp.resources.sc_recent_boosts
import space.sovereigncast.hopp.resources.sc_show_all
import space.sovereigncast.hopp.resources.sc_suggestions
import space.sovereigncast.hopp.resources.sc_tab_boosts
import space.sovereigncast.hopp.resources.sc_tab_episodes
import space.sovereigncast.hopp.resources.sc_top_month
import space.sovereigncast.hopp.resources.sc_via
import space.sovereigncast.hopp.resources.sheet_community
import space.sovereigncast.hopp.resources.show_auto_download
import space.sovereigncast.hopp.resources.show_episodes
import space.sovereigncast.hopp.resources.show_feed_error
import space.sovereigncast.hopp.resources.show_options
import space.sovereigncast.hopp.resources.show_subscribed
import space.sovereigncast.hopp.resources.show_unsubscribe
import space.sovereigncast.hopp.resources.show_unsubscribe_confirm
import space.sovereigncast.hopp.sovereigncast.Boost2UnlockEpisode
import space.sovereigncast.hopp.sovereigncast.Leaderboard
import space.sovereigncast.hopp.sovereigncast.ReleaseBoostingRound
import space.sovereigncast.hopp.sovereigncast.ScTarget
import space.sovereigncast.hopp.sovereigncast.Suggestion
import space.sovereigncast.hopp.ui.components.TabbedPage
import space.sovereigncast.hopp.ui.sovereigncast.BoosterRow
import space.sovereigncast.hopp.ui.sovereigncast.CommunityBoostRow
import space.sovereigncast.hopp.ui.sovereigncast.CommunityViewModel
import space.sovereigncast.hopp.ui.sovereigncast.ReleaseBoostingCard
import space.sovereigncast.hopp.ui.sovereigncast.ReleaseBoostingSheet
import space.sovereigncast.hopp.ui.sovereigncast.ScBadge
import space.sovereigncast.hopp.ui.sovereigncast.ScPaySheet
import space.sovereigncast.hopp.ui.sovereigncast.SuggestionRow
import space.sovereigncast.hopp.ui.sovereigncast.SuggestionSheet
import space.sovereigncast.hopp.ui.sovereigncast.UnlockCard
import space.sovereigncast.hopp.ui.sovereigncast.UnlockSheet
import space.sovereigncast.hopp.ui.value.BoostPill
import space.sovereigncast.hopp.ui.value.BoostSheet
import space.sovereigncast.hopp.ui.value.ValueBarViewModel

private enum class ShowTab { Episodes, Community, Boosts }

/**
 * A subscribed show: a quiet header (device feedback 2026-10-09 — the page was
 * too busy: auto-download and unsubscribe moved into the menu, the
 * description folds), a compact card per running Sovereigncast action, then
 * tabs that follow a swipe: the episodes, the community's suggestions and its
 * boosts (ADR 0019).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowScreen(podcastId: Long, onGone: () -> Unit, onOpenWallet: () -> Unit = {}, openCommunity: Boolean = false) {
    val nav = LocalEpisodeNav.current
    val vm: ShowViewModel = koinViewModel(key = "show-$podcastId") { parametersOf(podcastId) }
    val show by vm.show.collectAsState()
    val episodes by vm.episodeList.collectAsState()
    val query by vm.query.collectAsState()
    val found by vm.found.collectAsState()
    val count by vm.episodeCount.collectAsState()
    val listState = rememberLazyListState()
    OnNearEnd(listState) { seen -> vm.loadMore(seen) }
    val meta by vm.meta.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val gone by vm.gone.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    var boosting by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    LaunchedEffect(gone) { if (gone) onGone() }
    // The Sovereigncast layer (ADR 0018, phase 1) — only once the instance confirmed the show.
    val community: CommunityViewModel = koinViewModel(key = "community-$podcastId") { parametersOf(podcastId) }
    val layer by community.layer.collectAsState()
    val round by community.round.collectAsState()
    val unlocks by community.unlocks.collectAsState()
    val suggestions by community.suggestions.collectAsState()
    val board by community.leaderboard.collectAsState()
    val votes by community.votes.collectAsState()
    var paying by remember { mutableStateOf<Pair<ScTarget, String>?>(null) }
    var details by remember { mutableStateOf<Any?>(null) } // a round, an unlock or a suggestion
    val colors = MaterialTheme.colorScheme
    val caps = layer?.show?.capabilities
    val tabs = buildList {
        add(ShowTab.Episodes)
        if (caps?.suggestions == true) add(ShowTab.Community)
        if (caps?.leaderboard == true) add(ShowTab.Boosts)
    }
    val pager = rememberPagerState { tabs.size }
    // Opened to vote (Home's "Take part"): the Community tab, once it is known —
    // the capabilities arrive after the page — and only the first time.
    var communityShown by rememberSaveable { mutableStateOf(!openCommunity) }
    LaunchedEffect(tabs) {
        val at = tabs.indexOf(ShowTab.Community)
        if (!communityShown && at >= 0) { pager.scrollToPage(at); communityShown = true }
    }
    val labels = tabs.map {
        when (it) {
            ShowTab.Episodes -> stringResource(Res.string.sc_tab_episodes)
            ShowTab.Community -> stringResource(Res.string.sheet_community)
            ShowTab.Boosts -> stringResource(Res.string.sc_tab_boosts)
        }
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh(); community.load() }) {
        TabbedPage(
            labels, pager,
            header = {
                Column {
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(show?.imageUrl, 96.dp, corner = 16.dp)
                        Spacer(Modifier.size(14.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(show?.title ?: "", style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    show?.author?.let { Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (layer != null) ScBadge()
                                episodes?.let { Text(stringResource(Res.string.show_episodes, count.toInt()), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { confirm = true }) { Text("✓ " + stringResource(Res.string.show_subscribed)) }
                        // The whole show, not one episode (device feedback 2026-10-08).
                        if (meta?.value != null) BoostPill { boosting = true }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { sharing = true }) { Icon(Icons.Rounded.Share, stringResource(Res.string.share_title), tint = colors.onSurfaceVariant) }
                        val actions = space.sovereigncast.hopp.ui.LocalPlatformActions.current
                        show?.let { s -> ShowMenu(s.autoDownload, onAutoDownload = vm::setAutoDownload, onUnsubscribe = { confirm = true }, onCopyFeed = { actions.copyText(s.feedUrl, s.title) }) }
                    }
                    show?.lastError?.let {
                        Text(stringResource(Res.string.show_feed_error, it), color = colors.error, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    meta?.description?.let { d -> FoldingText(plainText(d)) }
                    // podcast:funding — the show's own way to be supported (ADR 0012).
                    meta?.funding?.firstOrNull()?.let { f ->
                        val uri = LocalUriHandler.current
                        Text(
                            "♥ " + (f.text?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.show_support)) + " ›",
                            Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { runCatching { uri.openUri(f.url) } }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // The Sovereigncast card: only while an action runs (ADR 0019).
                    round?.let { r ->
                        ReleaseBoostingCard(r, onOpen = { details = r }) { paying = ScTarget.ReleaseBoosting to r.episode.title }
                    }
                    unlocks.filterNot { it.unlocked }.forEach { u ->
                        UnlockCard(u, onOpen = { details = u }) { paying = ScTarget.Boost2Unlock(u.episode.episodeGuid) to u.episode.title }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            },
        ) { index ->
            when (tabs.getOrNull(index)) {
                ShowTab.Episodes, null -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    item(key = "search") { EpisodeSearchField(query, vm::search, found?.size) }
                    items(found ?: episodes.orEmpty(), key = { it.id }) { item ->
                        EpisodeRow(item, showPodcast = false, onClick = { nav.open(item) }) {
                            PlayButton(item)
                            EpisodeMenu(item)
                        }
                    }
                }
                ShowTab.Community -> LazyColumn(Modifier.fillMaxSize()) {
                    val l = layer
                    val list = suggestions.orEmpty()
                    item(key = "sug-h") { SectionTitle(stringResource(Res.string.sc_suggestions)) }
                    if (suggestions != null && list.isEmpty()) item(key = "sug-none") {
                        Text(stringResource(Res.string.sc_no_suggestions), Modifier.padding(16.dp), color = colors.onSurfaceVariant)
                    }
                    items(list, key = { "sug" + it.id }) { sg ->
                        SuggestionRow(sg, voted = l != null && "${l.show.podcastGuid}/${sg.id}" in votes, onOpen = { details = sg }) { community.vote(sg) }
                    }
                    if (l != null) item(key = "sc-via") { ScVia(l.host) }
                }
                ShowTab.Boosts -> LazyColumn(Modifier.fillMaxSize()) {
                    // Nothing but a spinner until the list is there: a lone footer
                    // would be the item the list keeps in view, and the arriving
                    // leaderboard would open at its end (device feedback 2026-10-09).
                    val b = board
                    if (b == null) item(key = "board-loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        leaderboard(b)
                        layer?.let { l -> item(key = "sc-via") { ScVia(l.host) } }
                    }
                }
            }
        }
    }

    when (val d = details) {
        is ReleaseBoostingRound -> ReleaseBoostingSheet(d, layer?.show?.communityPageUrl, onBoost = {
            details = null; paying = ScTarget.ReleaseBoosting to d.episode.title
        }) { details = null }
        is Boost2UnlockEpisode -> UnlockSheet(d, layer?.show?.communityPageUrl, onUnlock = {
            details = null; paying = ScTarget.Boost2Unlock(d.episode.episodeGuid) to d.episode.title
        }) { details = null }
        is Suggestion -> {
            // The live entry, so a vote shows at once.
            val sg = suggestions?.firstOrNull { it.id == d.id } ?: d
            val l = layer
            SuggestionSheet(sg, voted = l != null && "${l.show.podcastGuid}/${sg.id}" in votes, onVote = { community.vote(sg) }) { details = null }
        }
    }

    paying?.let { (target, title) ->
        ScPaySheet(community, target, title, satsPerMinute = round?.satsPerMinute?.takeIf { target is ScTarget.ReleaseBoosting }) { paying = null }
    }

    if (sharing) space.sovereigncast.hopp.ui.components.ShareSheet(space.sovereigncast.hopp.ui.components.ShareTarget.Show(podcastId)) { sharing = false }
    if (boosting) {
        val valueVm: ValueBarViewModel = koinViewModel()
        BoostSheet(
            valueVm, item = null, showTitle = show?.title.orEmpty(), positionMs = 0, podcastId = podcastId,
            onDismiss = { boosting = false }, onOpenWallet = { boosting = false; onOpenWallet() },
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text(stringResource(Res.string.show_unsubscribe_confirm, show?.title ?: "")) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.unsubscribe() }) { Text(stringResource(Res.string.show_unsubscribe)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

/** The show's settings, out of the way: auto-download and unsubscribe. */
@Composable
private fun ShowMenu(autoDownload: Boolean, onAutoDownload: (Boolean) -> Unit, onUnsubscribe: () -> Unit, onCopyFeed: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, stringResource(Res.string.show_options), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.show_auto_download)) },
                trailingIcon = { Switch(checked = autoDownload, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp)) },
                onClick = { onAutoDownload(!autoDownload) },
            )
            DropdownMenuItem(text = { Text(stringResource(Res.string.show_copy_feed)) }, onClick = { open = false; onCopyFeed() })
            DropdownMenuItem(text = { Text(stringResource(Res.string.show_unsubscribe)) }, onClick = { open = false; onUnsubscribe() })
        }
    }
}

/** A show description of a few lines that opens on a tap. */
@Composable
private fun FoldingText(text: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    // A tap opens it, a long press selects (device feedback 2026-10-12).
    SelectionContainer { Text(
        text,
        Modifier.fillMaxWidth().clickable(enabled = overflows || open) { open = !open }.padding(horizontal = 16.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
        maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!open) overflows = it.hasVisualOverflow },
    ) }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)

@Composable
private fun ScVia(host: String) = Text(
    stringResource(Res.string.sc_via, host), Modifier.fillMaxWidth().padding(16.dp),
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
)

/** This month's top boosts, the latest ones, the all-time boosters (device feedback 2026-10-09). */
private fun LazyListScope.leaderboard(b: Leaderboard) {
    item(key = "top-h") {
        val months = stringArrayResource(Res.array.months)
        val name = b.month.split("-").let { (y, m) -> months.getOrNull(m.toInt() - 1)?.let { "$it $y" } }
        SectionTitle(stringResource(Res.string.sc_top_month, name ?: b.month))
    }
    if (b.topBoosts.isEmpty()) item(key = "top-none") {
        Text(stringResource(Res.string.sc_no_boosts), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items(b.topBoosts, key = { "top" + it.rank + it.amountSats + it.sender }) { CommunityBoostRow(it, showRank = true) }
    if (b.recentBoosts.isNotEmpty()) {
        item(key = "recent-h") { SectionTitle(stringResource(Res.string.sc_recent_boosts)) }
        itemsIndexed(b.recentBoosts, key = { i, _ -> "recent$i" }) { _, r -> CommunityBoostRow(r, showRank = false) }
    }
    if (b.alltimeBoosters.isNotEmpty()) {
        item(key = "all-h") {
            SectionTitle(stringResource(Res.string.sc_alltime))
            Text(stringResource(Res.string.sc_alltime_note), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item(key = "all") {
            var all by rememberSaveable { mutableStateOf(false) }
            Column {
                (if (all) b.alltimeBoosters else b.alltimeBoosters.take(10)).forEach { BoosterRow(it) }
                if (!all && b.alltimeBoosters.size > 10) TextButton(onClick = { all = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(Res.string.sc_show_all, b.alltimeBoosters.size))
                }
            }
        }
    }
}

/** Show notes come as HTML; the header only needs readable text. */
internal fun plainText(html: String): String =
    html.replace(Regex("<br\\s*/?>|</p>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("\n{3,}"), "\n\n").trim()
