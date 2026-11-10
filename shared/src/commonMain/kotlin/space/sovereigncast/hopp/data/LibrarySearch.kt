package space.sovereigncast.hopp.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.feed.Chapter
import space.sovereigncast.hopp.feed.ChaptersParser
import space.sovereigncast.hopp.feed.NotesChapters
import space.sovereigncast.hopp.model.Episode
import space.sovereigncast.hopp.model.Podcast
import space.sovereigncast.hopp.nostr.Event
import space.sovereigncast.hopp.platform.ioDispatcher
import space.sovereigncast.hopp.ui.components.decodeEntities

/*
 * The library search (maintainer 2026-11-10): the subscribed shows, their episodes' titles, show notes
 * and persons, their chapters and the comments read on them — on the device, nothing asked of anyone.
 * Every text is kept folded beside what it is folded from ([fold]: lower case, "ä" as "ae"), so a
 * plain SQL LIKE finds a word anywhere, inside a German compound too — "bibliothek" in
 * "Bitcoinbibliothek", which a full-text index, matching word starts only, would not.
 */

/** [text] in lower case, "ä" "ö" "ü" "ß" as "ae" "oe" "ue" "ss" — and for each of its characters the index in [text] it comes from. */
internal class Folded(val text: String, val from: IntArray)

internal fun fold(text: String): Folded {
    val out = StringBuilder(text.length + 16)
    val from = IntArray(text.length * 2)
    var n = 0
    text.forEachIndexed { i, c ->
        when (val l = c.lowercaseChar()) {
            'ä' -> { out.append("ae"); from[n++] = i; from[n++] = i }
            'ö' -> { out.append("oe"); from[n++] = i; from[n++] = i }
            'ü' -> { out.append("ue"); from[n++] = i; from[n++] = i }
            'ß' -> { out.append("ss"); from[n++] = i; from[n++] = i }
            else -> { out.append(l); from[n++] = i }
        }
    }
    return Folded(out.toString(), from.copyOf(n))
}

internal fun folded(text: String): String = fold(text).text

/** Show notes as text: no tags, entities decoded, every run of spaces and line breaks one space. */
internal fun plainText(html: String?): String =
    if (html.isNullOrBlank()) "" else decodeEntities(TAG.replace(html, " ")).replace(SPACES, " ").trim()

private val TAG = Regex("<[^>]*>")
private val SPACES = Regex("\\s+")

/** Show notes longer than this are searched in their first part: some feeds put a whole transcript there. */
private const val MAX_TEXT = 40_000

internal fun searchText(e: Episode): String =
    folded(listOfNotNull(e.title, plainText(e.description), e.persons.joinToString(" ") { it.name }).joinToString(" ").take(MAX_TEXT))

internal fun searchText(p: Podcast, fallbackTitle: String? = null): String = folded(
    listOfNotNull(p.title ?: fallbackTitle, p.author, plainText(p.description), p.categories.joinToString(" "), p.persons.joinToString(" ") { it.name })
        .joinToString(" ").take(MAX_TEXT),
)

/** The chapters the feed itself gives an episode — its Podlove chapters, else a list in its notes — as the player finds them. */
internal fun feedChapters(e: Episode, durationSeconds: Long?): List<Chapter> =
    e.chapters.ifEmpty { NotesChapters.parse(e.description, durationSeconds ?: e.durationSeconds) }.searchable()

private fun List<Chapter>.searchable() = filter { it.inToc && it.title.isNotBlank() }

/** What tells [chapters] apart from others, for knowing whether they changed. */
internal fun chapterSig(chapters: List<Chapter>): Long = chapters.joinToString("\n") { "${it.startSeconds}|${it.title}" }.hashCode().toLong()

/** A piece of [text] with where the words found in it are, for showing them marked. */
data class Snippet(val text: String, val marks: List<IntRange>)

/** Where [words] (folded) stand in [text]: ranges of [text], in order, overlaps joined. */
fun marks(text: String, words: List<String>): List<IntRange> {
    if (words.isEmpty() || text.isEmpty()) return emptyList()
    val f = fold(text)
    val found = words.flatMap { w ->
        generateSequence(f.text.indexOf(w).takeIf { it >= 0 }) { i -> f.text.indexOf(w, i + w.length).takeIf { it >= 0 } }
            .map { i -> f.from[i]..f.from[i + w.length - 1] }.toList()
    }.sortedBy { it.first }
    return found.fold(mutableListOf<IntRange>()) { out, r ->
        val last = out.lastOrNull()
        if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out += r
        out
    }
}

/** [text] around the first of [words] found in it, at most about [length] characters, cut at spaces; null when none is in it. */
fun snippet(text: String, words: List<String>, length: Int = 150): Snippet? {
    val all = marks(text, words)
    val first = all.firstOrNull() ?: return null
    var start = (first.first - length / 3).coerceAtLeast(0)
    if (start > 0) text.indexOf(' ', start).takeIf { it in start until first.first }?.let { start = it + 1 }
    var end = (start + length).coerceAtMost(text.length)
    if (end < first.last + 1) end = (first.last + 1).coerceAtMost(text.length)
    if (end < text.length) text.lastIndexOf(' ', end).takeIf { it > first.last }?.let { end = it }
    val lead = if (start > 0) "… " else ""
    val shift = lead.length - start
    val cut = lead + text.substring(start, end) + if (end < text.length) " …" else ""
    return Snippet(cut, all.filter { it.first >= start && it.last < end }.map { (it.first + shift)..(it.last + shift) })
}

data class ShowHit(val id: Long, val feedUrl: String, val title: String, val author: String?, val imageUrl: String?, val episodes: Long)
data class EpisodeHit(val item: EpisodeItem, val snippet: Snippet?)
data class ChapterHit(val item: EpisodeItem, val startMs: Long, val title: String)
data class CommentHit(val item: EpisodeItem, val id: String, val pubkey: String, val author: String?, val at: Long, val snippet: Snippet)

/** What the library holds for a search: [words] as compared, the first hits of each kind, and how many there are in all. */
data class LibraryHits(
    val words: List<String>,
    val shows: List<ShowHit> = emptyList(),
    val episodes: List<EpisodeHit> = emptyList(),
    val chapters: List<ChapterHit> = emptyList(),
    val comments: List<CommentHit> = emptyList(),
    val episodeCount: Long = 0,
    val chapterCount: Long = 0,
    val commentCount: Long = 0,
) {
    val total: Long get() = shows.size + episodeCount + chapterCount + commentCount
}

/**
 * Searching the library and keeping what it searches. The shows' and episodes' texts are written
 * with them (PodcastRepository); [catchUp] writes what is missing — the rows from before, an
 * episode's feed chapters, once the chapter files and comments kept before the search was there —
 * in the background, after a subscription or a refresh too ([later]).
 */
class LibrarySearch(private val db: HoppDatabase, private val scope: CoroutineScope? = null) {
    private val sq = db.searchQueries
    private val gate = Mutex()

    /** [query]'s hits, [limit] of each kind; [newest] first, else the oldest. */
    suspend fun find(query: String, limit: Long = 3, newest: Boolean = true): LibraryHits = withContext(ioDispatcher) {
        val words = words(query)
        if (words.isEmpty()) return@withContext LibraryHits(words)
        val (a, b, c, d) = (words.map { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" } + List(4) { "%" }).take(4)
        val direction = if (newest) 1L else -1L
        val shows = sq.searchShows(a, b, c, d).awaitAsList().map { ShowHit(it.id, it.feed_url, it.title, it.author, it.image_url, it.episodes) }
        val episodes = sq.searchEpisodes(a, b, c, d, direction, limit).awaitAsList().map { r ->
            val item = episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url, r.chapters_url,
                r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url, r.favorite_at, r.enclosure_length)
            val notes = runCatching { json.decodeFromString(Episode.serializer(), r.meta_json).description }.getOrNull()
            EpisodeHit(item, snippet(plainText(notes), words))
        }
        val chapters = sq.searchChapters(a, b, c, d, direction, limit).awaitAsList().map { r ->
            ChapterHit(episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url, r.chapters_url,
                r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url, r.favorite_at, r.enclosure_length),
                r.start_ms, r.chapter_title)
        }
        val comments = sq.searchComments(a, b, c, d, direction, limit).awaitAsList().map { r ->
            val text = r.content.replace(SPACES, " ").trim()
            CommentHit(episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url, r.chapters_url,
                r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url, r.favorite_at, r.enclosure_length),
                r.comment_id, r.pubkey, r.author_name, r.comment_at, snippet(text, words) ?: Snippet(text.take(150), emptyList()))
        }
        LibraryHits(
            words, shows, episodes, chapters, comments,
            sq.countEpisodes(a, b, c, d).awaitAsOne(), sq.countChapters(a, b, c, d).awaitAsOne(), sq.countComments(a, b, c, d).awaitAsOne(),
        )
    }

    /** [catchUp] in the background. */
    fun later() { scope?.launch { runCatching { catchUp() } } }

    /** Whatever is not yet searchable made so. Each part in batches, so a large library does not hold the database long. */
    suspend fun catchUp() = gate.withLock {
        withContext(ioDispatcher) {
            val shows = sq.showsWithoutText().awaitAsList()
            if (shows.isNotEmpty()) db.transaction {
                for (r in shows) sq.setShowText(runCatching { searchText(json.decodeFromString(Podcast.serializer(), r.meta_json)) }.getOrDefault(""), r.id)
            }
            while (true) {
                val rows = sq.episodesWithoutText(BATCH).awaitAsList()
                if (rows.isEmpty()) break
                db.transaction {
                    for (r in rows) sq.setEpisodeText(runCatching { json.decodeFromString(Episode.serializer(), r.meta_json).let { searchText(it.copy(title = it.title ?: r.title)) } }
                        .getOrElse { folded(r.title.orEmpty()) }, r.id)
                }
            }
            while (true) {
                val rows = sq.episodesWithoutChapters(BATCH).awaitAsList()
                if (rows.isEmpty()) break
                db.transaction {
                    for (r in rows) {
                        val chapters = runCatching { feedChapters(json.decodeFromString(Episode.serializer(), r.meta_json), r.duration_seconds) }.getOrDefault(emptyList())
                        sq.deleteFeedChapters(r.id)
                        // A chapter file loaded is what the player shows: the feed's own stay out then.
                        if (sq.hasFileChapters(r.id).awaitAsOne() == 0L) chapters.forEach { insertChapter(r.id, r.podcast_id, it, fromFile = false) }
                        sq.setChapterSig(chapterSig(chapters), r.id)
                    }
                }
            }
            if (sq.done().awaitAsOneOrNull() != CATCH_UP) {
                for (r in sq.cachedChapterFiles().awaitAsList()) indexChapterFile(db, r.episode_id, runCatching { ChaptersParser.parse(r.json) }.getOrDefault(emptyList()))
                for (r in sq.commentItems().awaitAsList()) {
                    val events = db.commentQueries.events(r.item_tag).awaitAsList().mapNotNull { runCatching { json.decodeFromString(Event.serializer(), it) }.getOrNull() }
                    db.transaction { indexComments(db, r.item_tag, events, strings(r.muted_json), strings(r.roots_json)) }
                }
                sq.setDone(CATCH_UP)
            }
        }
    }

    private suspend fun insertChapter(episodeId: Long, podcastId: Long, c: Chapter, fromFile: Boolean) =
        sq.insertChapter(episodeId, podcastId, (c.startSeconds * 1000).toLong(), c.title.trim(), folded(c.title.trim()), if (fromFile) 1 else 0)

    companion object {
        private const val BATCH = 300L
        /** Raised when what was kept before has to be read again. */
        private const val CATCH_UP = "1"

        /** [query]'s words as compared: folded, each once, the four longest — LIKE takes four. */
        fun words(query: String): List<String> = searchWords(query).map(::folded).distinct().sortedByDescending { it.length }.take(4)

        private fun strings(text: String): Set<String> =
            runCatching { json.decodeFromString(ListSerializer(String.serializer()), text).toSet() }.getOrDefault(emptySet())
    }
}

/** A chapter file just loaded: its chapters are the episode's, the feed's own go. */
internal suspend fun indexChapterFile(db: HoppDatabase, episodeId: Long, chapters: List<Chapter>) {
    val sq = db.searchQueries
    db.transaction {
        val podcastId = sq.podcastOfEpisode(episodeId).awaitAsOneOrNull() ?: return@transaction
        sq.deleteChapters(episodeId)
        chapters.searchable().forEach { c -> sq.insertChapter(episodeId, podcastId, (c.startSeconds * 1000).toLong(), c.title.trim(), folded(c.title.trim()), 1) }
    }
}

/**
 * An episode's comments as kept for the search, from every event kept for it: the notes and replies
 * (kinds 1 and 1111) with text — not the show's own notes on it ([roots]), not who is [muted].
 * Inside the transaction that kept them.
 */
internal suspend fun indexComments(db: HoppDatabase, itemTag: String, events: List<Event>, muted: Set<String>, roots: Set<String>) {
    val sq = db.searchQueries
    sq.deleteComments(itemTag)
    events.asSequence()
        .filter { (it.kind == 1 || it.kind == 1111) && it.content.isNotBlank() && it.pubkey !in muted && it.id !in roots }
        .distinctBy { it.id }
        .forEach { sq.insertComment(itemTag, it.id, it.pubkey, it.created_at, it.content, folded(it.content.replace(SPACES, " ").take(MAX_TEXT))) }
}
