package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import space.sovereigncast.hopp.data.ChapterHit
import space.sovereigncast.hopp.data.CommentHit
import space.sovereigncast.hopp.data.EpisodeHit
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.data.LibraryHits
import space.sovereigncast.hopp.data.LibrarySearch
import space.sovereigncast.hopp.data.ShowHit
import space.sovereigncast.hopp.data.Snippet
import space.sovereigncast.hopp.data.marks
import space.sovereigncast.hopp.nostr.npub
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.discover_all
import space.sovereigncast.hopp.resources.library_shows
import space.sovereigncast.hopp.resources.person_episodes
import space.sovereigncast.hopp.resources.play
import space.sovereigncast.hopp.resources.sc_tab_episodes
import space.sovereigncast.hopp.resources.search_all
import space.sovereigncast.hopp.resources.search_hits
import space.sovereigncast.hopp.resources.search_kind_chapter
import space.sovereigncast.hopp.resources.search_kind_comment
import space.sovereigncast.hopp.resources.search_kind_episode
import space.sovereigncast.hopp.resources.search_kind_show
import space.sovereigncast.hopp.resources.search_library
import space.sovereigncast.hopp.resources.search_newest
import space.sovereigncast.hopp.resources.search_oldest
import space.sovereigncast.hopp.resources.search_play_from
import space.sovereigncast.hopp.resources.search_scope
import space.sovereigncast.hopp.resources.search_title
import space.sovereigncast.hopp.resources.sheet_chapters
import space.sovereigncast.hopp.resources.sheet_comments
import space.sovereigncast.hopp.resources.show_subscribed
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.formatDate
import space.sovereigncast.hopp.ui.components.formatTime

/*
 * The library search (maintainer 2026-11-10): in Discover, above what the directories find, the first
 * hits of each kind; all of them on a page of their own.
 */

/** In Discover: "In your library", its first shows, episodes and chapters — the comments only when nothing else is found. */
internal fun LazyListScope.librarySection(
    hits: LibraryHits,
    onAll: () -> Unit,
    onShow: (ShowHit) -> Unit,
    onEpisode: (EpisodeItem) -> Unit,
    onPlay: (EpisodeItem) -> Unit,
    onPlayAt: (EpisodeItem, Long) -> Unit,
) {
    item(key = "library") {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.search_library), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onAll) { Text(stringResource(Res.string.search_all, hits.total.toInt()) + " ›") }
        }
    }
    if (hits.shows.isNotEmpty()) {
        item(key = "library-shows") { Group(stringResource(Res.string.library_shows)) }
        items(hits.shows.take(SHOWN), key = { "ls" + it.id }) { SearchShowRow(it, hits.words, onClick = { onShow(it) }) }
    }
    if (hits.episodes.isNotEmpty()) {
        item(key = "library-episodes") { Group(stringResource(Res.string.sc_tab_episodes)) }
        items(hits.episodes, key = { "le" + it.item.id }) { SearchEpisodeRow(it, hits.words, onOpen = { onEpisode(it.item) }, onPlay = { onPlay(it.item) }) }
    }
    if (hits.chapters.isNotEmpty()) {
        item(key = "library-chapters") { Group(stringResource(Res.string.sheet_chapters)) }
        items(hits.chapters.take(2), key = { "lc" + it.item.id + ":" + it.startMs }) { SearchChapterRow(it, hits.words, onPlay = { onPlayAt(it.item, it.startMs) }) }
    }
    if (hits.shows.isEmpty() && hits.episodes.isEmpty() && hits.chapters.isEmpty() && hits.comments.isNotEmpty()) {
        item(key = "library-comments") { Group(stringResource(Res.string.sheet_comments)) }
        items(hits.comments.take(2), key = { "lm" + it.id }) { SearchCommentRow(it, hits.words, onOpen = { onEpisode(it.item) }) }
    }
}

/** How many of the shows found Discover shows. */
private const val SHOWN = 3

/** All the library's hits for [query]: a kind at a time, newest or oldest first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibrarySearchScreen(
    query: String,
    onShow: (ShowHit) -> Unit,
    onEpisode: (Long) -> Unit,
    vm: LibrarySearchViewModel = koinViewModel(key = "search:$query") { parametersOf(query) },
) {
    val hits by vm.hits.collectAsState()
    val kind by vm.kind.collectAsState()
    val newest by vm.newest.collectAsState()
    val player: PlayerController = koinInject()
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text(stringResource(Res.string.search_title, query), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineSmall) }
        val h = hits
        if (h == null) {
            item { Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
            return@LazyColumn
        }
        val counts = listOf(
            SearchKind.All to h.total, SearchKind.Shows to h.shows.size.toLong(), SearchKind.Episodes to h.episodeCount,
            SearchKind.Chapters to h.chapterCount, SearchKind.Comments to h.commentCount,
        ).filter { (k, n) -> k == SearchKind.All || n > 0 }
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                counts.forEach { (k, n) ->
                    FilterChip(selected = kind == k, onClick = { vm.kind.value = k }, label = { Text(kindLabel(k) + " " + n) })
                }
            }
        }
        val count = counts.firstOrNull { it.first == kind }?.second ?: 0
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(Res.plurals.search_hits, count.toInt(), count.toInt()), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                TextButton(onClick = { vm.newest.value = !newest }) { Text(stringResource(if (newest) Res.string.search_newest else Res.string.search_oldest) + " ⇅") }
            }
        }
        val all = kind == SearchKind.All
        if (all || kind == SearchKind.Shows) items(h.shows, key = { "s" + it.id }) {
            SearchShowRow(it, h.words, kind = if (all) stringResource(Res.string.search_kind_show) else null, onClick = { onShow(it) })
        }
        val entries = buildList {
            if (all || kind == SearchKind.Episodes) h.episodes.forEach { add(Entry(it.item.publishedAt ?: 0, "e" + it.item.id, it)) }
            if (all || kind == SearchKind.Chapters) h.chapters.forEach { add(Entry(it.item.publishedAt ?: 0, "c" + it.item.id + ":" + it.startMs, it)) }
            if (all || kind == SearchKind.Comments) h.comments.forEach { add(Entry(it.at, "m" + it.id + ":" + it.item.id, it)) }
        }.let { l -> if (newest) l.sortedByDescending { it.at } else l.sortedBy { it.at } }
        items(entries, key = { it.key }) { e ->
            when (val hit = e.hit) {
                is EpisodeHit -> SearchEpisodeRow(hit, h.words, kind = if (all) stringResource(Res.string.search_kind_episode) else null,
                    onOpen = { onEpisode(hit.item.id) }, onPlay = { player.play(hit.item, expand = false) })
                is ChapterHit -> SearchChapterRow(hit, h.words, kind = if (all) stringResource(Res.string.search_kind_chapter) else null,
                    onPlay = { player.playAt(hit.item, hit.startMs) })
                is CommentHit -> SearchCommentRow(hit, h.words, kind = if (all) stringResource(Res.string.search_kind_comment) else null,
                    onOpen = { onEpisode(hit.item.id) })
            }
        }
        item {
            Text(stringResource(Res.string.search_scope), Modifier.padding(16.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainer).padding(12.dp),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

private class Entry(val at: Long, val key: String, val hit: Any)

enum class SearchKind { All, Shows, Episodes, Chapters, Comments }

@Composable
private fun kindLabel(k: SearchKind): String = when (k) {
    SearchKind.All -> stringResource(Res.string.discover_all)
    SearchKind.Shows -> stringResource(Res.string.library_shows)
    SearchKind.Episodes -> stringResource(Res.string.sc_tab_episodes)
    SearchKind.Chapters -> stringResource(Res.string.sheet_chapters)
    SearchKind.Comments -> stringResource(Res.string.sheet_comments)
}

class LibrarySearchViewModel(private val query: String, private val search: LibrarySearch) : ViewModel() {
    val kind = MutableStateFlow(SearchKind.All)
    val newest = MutableStateFlow(true)

    @OptIn(ExperimentalCoroutinesApi::class)
    val hits: StateFlow<LibraryHits?> = newest.mapLatest { n -> runCatching { search.find(query, LIMIT, n) }.getOrNull() ?: LibraryHits(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    companion object {
        /** Of each kind; the counts above them are all there are. */
        const val LIMIT = 200L
    }
}

// ── The rows ─────────────────────────────────────────────────────────────────

/** [text] with [words] marked, as the search found them. */
@Composable
private fun marked(text: String, words: List<String>): AnnotatedString = markedAt(text, remember(text, words) { marks(text, words) })

@Composable
private fun markedAt(text: String, ranges: List<IntRange>): AnnotatedString {
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    return remember(text, ranges, tint) {
        buildAnnotatedString {
            append(text)
            ranges.forEach { addStyle(SpanStyle(fontWeight = FontWeight.Bold, background = tint), it.first, it.last + 1) }
        }
    }
}

@Composable
private fun marked(s: Snippet) = markedAt(s.text, s.marks)

@Composable
private fun Group(text: String) = Text(
    text.uppercase(), Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** What a row of the page with every kind is. */
@Composable
private fun KindLabel(text: String) {
    val colors = MaterialTheme.colorScheme
    Text(text.uppercase(), Modifier.clip(RoundedCornerShape(4.dp)).background(colors.secondaryContainer).padding(horizontal = 5.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colors.onSecondaryContainer)
}

@Composable
private fun HitRow(onClick: () -> Unit, cover: String?, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(cover, 48.dp, corner = 8.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
        trailing()
    }
}

@Composable
internal fun SearchShowRow(hit: ShowHit, words: List<String>, kind: String? = null, onClick: () -> Unit) {
    HitRow(onClick, hit.imageUrl) {
        kind?.let { KindLabel(it) }
        Text(marked(hit.title, words), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(Res.string.show_subscribed) + " · " + pluralStringResource(Res.plurals.person_episodes, hit.episodes.toInt(), hit.episodes.toInt()),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SearchEpisodeRow(hit: EpisodeHit, words: List<String>, kind: String? = null, onOpen: () -> Unit, onPlay: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    HitRow(onOpen, hit.item.imageUrl ?: hit.item.podcastImageUrl, trailing = {
        IconButton(onClick = onPlay) { Icon(HoppIcons.Play, stringResource(Res.string.play), Modifier.size(18.dp), tint = colors.primary) }
    }) {
        kind?.let { KindLabel(it) }
        Text(marked(hit.item.title, words), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(hit.item.podcastTitle, formatDate(hit.item.publishedAt).takeIf { it.isNotEmpty() }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        hit.snippet?.let { Text(marked(it), style = MaterialTheme.typography.bodySmall, color = colors.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
internal fun SearchChapterRow(hit: ChapterHit, words: List<String>, kind: String? = null, onPlay: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val at = formatTime(hit.startMs)
    val playFrom = stringResource(Res.string.search_play_from, at)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).semantics { contentDescription = playFrom }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(16.dp)).background(colors.secondaryContainer).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(HoppIcons.Play, null, Modifier.size(10.dp), tint = colors.onSecondaryContainer)
            Text(at, style = MaterialTheme.typography.labelLarge, color = colors.onSecondaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            kind?.let { KindLabel(it) }
            Text(marked(hit.title, words), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(hit.item.title, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun SearchCommentRow(hit: CommentHit, words: List<String>, kind: String? = null, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    HitRow(onOpen, hit.item.imageUrl ?: hit.item.podcastImageUrl) {
        kind?.let { KindLabel(it) }
        val who = hit.author?.takeIf { it.isNotBlank() } ?: npub(hit.pubkey).let { it.take(10) + "…" + it.takeLast(4) }
        Text(listOfNotNull(who, hit.item.podcastTitle).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(hit.item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(marked(hit.snippet), style = MaterialTheme.typography.bodySmall, color = colors.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
