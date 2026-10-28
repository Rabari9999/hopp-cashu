package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import space.sovereigncast.hopp.data.episodeMatches
import space.sovereigncast.hopp.data.searchWords
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import space.sovereigncast.hopp.directory.DirectoryShow
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.*
import space.sovereigncast.hopp.resources.discover_add_button
import space.sovereigncast.hopp.resources.preview_failed
import space.sovereigncast.hopp.resources.result_failed
import space.sovereigncast.hopp.resources.show_episodes
import space.sovereigncast.hopp.resources.show_subscribed
import space.sovereigncast.hopp.ui.components.formatDate
import space.sovereigncast.hopp.ui.components.formatTime

/**
 * A show before subscribing (ADR 0008): loaded from its own feed, so it shows
 * what the show publishes now — value block included. Subscribing leads on
 * to the show's page; an already subscribed show opens it.
 */
/** An episode a shared link names, and where to start it. */
data class SharedEpisode(val guid: String, val startSeconds: Long? = null)

@Composable
fun ShowPreviewScreen(
    feedUrl: String, onOpen: (Long) -> Unit, known: DirectoryShow? = null,
    shared: SharedEpisode? = null, onOpenShared: (podcastId: Long, guid: String, startSeconds: Long?) -> Unit = { _, _, _ -> },
) {
    val vm: ShowPreviewViewModel = koinViewModel(key = "preview-$feedUrl") { parametersOf(feedUrl) }
    val state by vm.state.collectAsState()
    val subscribedId by vm.subscribedId.collectAsState()
    val working by vm.working.collectAsState()
    val error by vm.error.collectAsState()
    val layer by vm.layer.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val colors = MaterialTheme.colorScheme

    val loaded = state as? PreviewState.Loaded
    val p = loaded?.feed?.podcast
    val all = remember(loaded) { loaded?.feed?.episodes.orEmpty().sortedByDescending { it.publishedAt ?: 0 } }
    var shown by remember(loaded) { mutableIntStateOf(PAGE) }
    // Searching before subscribing too (device feedback 2026-10-11) — in the feed already loaded.
    var query by rememberSaveable { mutableStateOf("") }
    val words = remember(query) { searchWords(query).takeIf { query.trim().length >= 2 } }
    val found = remember(all, words) { words?.let { w -> all.filter { episodeMatches(it.title, it.description, w) } } }
    val episodes = found ?: all.take(shown)
    val listState = rememberLazyListState()
    OnNearEnd(listState) { seen -> if (seen > shown && shown < all.size) shown += PAGE }
    val count = loaded?.feed?.episodes?.size ?: 0
    val failed = state as? PreviewState.Failed
    // What the directory already said shows at once; the feed fills in the rest.
    val title = p?.title ?: known?.title
    if (failed != null && title == null) {
        EmptyState(stringResource(Res.string.preview_failed, failed.reason), feedUrl)
        return
    }
    LazyColumn(state = listState) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(p?.imageUrl ?: known?.imageUrl, 112.dp, corner = 18.dp)
                Spacer(Modifier.size(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SelectionContainer { Text(title ?: feedUrl, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    (p?.author ?: known?.author)?.let { Text(it, color = colors.onSurfaceVariant, maxLines = 1) }
                    CategoryTags(p?.categories ?: known?.categories.orEmpty())
                    if (loaded != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (layer != null) space.sovereigncast.hopp.ui.sovereigncast.ScBadge()
                        Text(
                            listOfNotNull(stringResource(Res.string.show_episodes, count), "⚡ V4V".takeIf { p?.value != null }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        )
                    }
                    val id = subscribedId
                    if (id != null) OutlinedButton(onClick = { onOpen(id) }) { Text("✓ " + stringResource(Res.string.show_subscribed)) }
                    else Button(onClick = { vm.subscribe(onOpen) }, enabled = !working && failed == null) {
                        if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                        else Text(stringResource(Res.string.discover_add_button))
                    }
                }
            }
            error?.let { Text(stringResource(Res.string.result_failed, it), color = colors.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            failed?.let { Text(stringResource(Res.string.preview_failed, it.reason), color = colors.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            p?.description?.let { d ->
                SelectionContainer {
                    Text(plainText(d), color = colors.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            // Most use before subscribing: what the show sounds like.
            p?.let { TrailerLine(it.trailers, it.title.orEmpty(), it.imageUrl) }
            if (state == PreviewState.Loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        // The community before subscribing (maintainer 2026-10-27): a new show has no episodes
        // yet, but its listeners' topic suggestions — to read here; voting and suggesting once subscribed.
        if (layer?.show?.capabilities?.suggestions == true) suggestions?.let { list ->
            item(key = "sc-sug") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(Res.string.sc_suggestions), style = MaterialTheme.typography.titleMedium)
                    if (list.isEmpty()) Text(stringResource(Res.string.sc_no_suggestions), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    list.take(5).forEach { sg ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant).padding(12.dp)) {
                            Text(sg.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(Res.string.sc_votes, sg.voteCount), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                    if (subscribedId == null) Text(stringResource(Res.string.preview_sc_join), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        // The episode someone shared (device feedback 2026-10-12): on top, a tap
        // from listening — subscribing on the way when need be.
        val sharedEpisode = shared?.let { s -> all.firstOrNull { it.guid == s.guid } }
        if (shared != null && sharedEpisode != null) item(key = "shared") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp))
                    .background(colors.surfaceVariant).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(Res.string.share_shared_episode), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(sharedEpisode.title ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                shared.startSeconds?.let { Text(stringResource(Res.string.share_from, formatTime(it * 1000)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                val id = subscribedId
                if (id != null) Button(onClick = { onOpenShared(id, shared.guid, shared.startSeconds) }) { Text(stringResource(Res.string.share_open_episode)) }
                else Button(onClick = { vm.subscribe { pid -> onOpenShared(pid, shared.guid, shared.startSeconds) } }, enabled = !working) {
                    Text(stringResource(Res.string.share_subscribe_listen))
                }
            }
        }
        if (all.isNotEmpty()) item(key = "search") { EpisodeSearchField(query, { query = it }, found?.size) }
        // By index: a feed can repeat a guid, and a repeated key crashes (code review 2026-10-17, H7).
        itemsIndexed(episodes, key = { i, _ -> "e$i" }) { _, e ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(e.title ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(formatDate(e.publishedAt).takeIf { it.isNotEmpty() }, e.durationSeconds?.let { formatTime(it * 1000) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
