package space.sovereigncast.hopp.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.platform.ioDispatcher
import space.sovereigncast.hopp.sync.SyncDocs.bool
import space.sovereigncast.hopp.sync.SyncDocs.long
import space.sovereigncast.hopp.sync.SyncDocs.text
import kotlin.time.Duration.Companion.days

/**
 * Progress and favourites in step (package 4 of docs/tasks/nostr-sync.md):
 * where the listener stopped, what they heard, what they marked — for
 * episodes in progress or heard in the last 90 days, and every favourite.
 * An entry for an episode this device does not have yet waits for its feed;
 * one for the episode playing here waits until it pauses.
 */
class ProgressDocument(
    private val db: HoppDatabase,
    private val episodes: EpisodeRepository,
    private val nowSeconds: () -> Long,
    /** Whether episode id is playing here now. */
    private val playing: (Long) -> Boolean = { false },
    /** The episode loaded in the player, paused, follows a position from elsewhere. */
    private val adopt: suspend (id: Long, positionMs: Long, played: Boolean) -> Unit = { _, _, _ -> },
    /** The feed of a show whose episode an entry is about, read at once where it is not here yet. */
    private val catchUp: FeedCatchUp? = null,
) : SyncedDocument {
    override val name = SyncDocs.PROGRESS

    override fun expired(key: String, entry: SyncEntry, nowMs: Long): Boolean =
        key.startsWith("pos\n") && entry.t < nowMs - KEPT.inWholeMilliseconds

    // The shows heard from their oldest episode on get their "heard up to" — once per start.
    override suspend fun prepare() { episodes.deriveHeardUpToOnce(); episodes.announceListenedOnce() }

    override suspend fun describe(entries: Map<String, SyncEntry>): String = withContext(ioDispatcher) {
        val live = entries.filterValues { !it.del }
        val marks = live.filterKeys { it.startsWith(UPTO) }
        val favs = live.count { it.key.startsWith("fav\n") }
        val pos = live.filter { it.key.startsWith("pos\n") }
        val placed = pos.count { (it.value.d.long("pos") ?: 0) > 0 }
        val unheard = pos.count { (it.value.d.long("pos") ?: 0) == 0L && it.value.d.bool("played") != true }
        val heard = pos.filter { (it.value.d.long("pos") ?: 0) == 0L && it.value.d.bool("played") == true }
        val markShows = marks.values.mapNotNull { it.d.text("show") }.toSet()
        val shows = db.podcastQueries.selectAll().awaitAsList()
        val byShow = heard.values.groupBy { it.d.text("show") }
        var noMark = 0; var notSubscribed = 0; var unknown = 0; var notCovered = 0
        val covered = redundant(live).size
        for ((show, list) in byShow) {
            val podcast = shows.firstOrNull { SyncDocs.showKey(it.podcast_guid, it.feed_url) == show } ?: shows.firstOrNull { "url:${it.feed_url}" == show }
            if (podcast == null) { notSubscribed += list.size; continue }
            if (show !in markShows) { noMark += list.size; continue }
            val dates = db.episodeQueries.publishedOf(podcast.id).awaitAsList().associate { it.guid to it.published_at }
            list.forEach { e -> if (dates[e.d.text("guid")] == null) unknown++ else notCovered++ }
        }
        notCovered -= covered
        "${marks.size} heard-up-to, ${favs} favourites, $placed with a position, $unheard unheard, ${heard.size} heard " +
            "(${byShow.size} shows: $covered covered, $noMark without a mark, $notSubscribed not subscribed, $unknown not in the feed here, ${notCovered.coerceAtLeast(0)} newer or after the mark)"
    }

    /**
     * An episode's "heard" entry its show's "heard up to" says already (maintainer 2026-10-30: the
     * single entries of marking before 0.3.2 made the document too large for relays): heard, at its
     * start, no newer than the mark, and out before it (and not before where it starts) — by this
     * device's copy of the feed; an episode it does not have stays. Applied again it would change
     * nothing: the mark goes first.
     */
    override suspend fun redundant(entries: Map<String, SyncEntry>): Set<String> = withContext(ioDispatcher) {
        val marks = entries.filter { (k, e) -> k.startsWith(UPTO) && !e.del }.values
            .mapNotNull { e -> e.d.text("show")?.let { s -> e.d.long("before")?.let { b -> s to Mark(b, e.d.long("from"), e.t) } } }.toMap()
        if (marks.isEmpty()) return@withContext emptySet()
        val shows = db.podcastQueries.selectAll().awaitAsList()
        val dates = mutableMapOf<String, Map<String, Long?>>()
        val recent = entries.filter { (k, e) -> k.startsWith("pos\n") && !e.del && e.d.long("listened") != null }
            .entries.sortedByDescending { it.value.t }.take(RECENT).mapTo(mutableSetOf()) { it.key }
        entries.filter { (k, e) ->
            if (!k.startsWith("pos\n") || e.del || e.d.bool("played") != true || (e.d.long("pos") ?: 0) != 0L) return@filter false
            // The newest listened to stay single: the mark does not say they were played (maintainer 2026-11-02).
            if (k in recent) return@filter false
            val show = e.d.text("show") ?: return@filter false
            val guid = e.d.text("guid") ?: return@filter false
            val mark = marks[show] ?: return@filter false
            if (e.t > mark.t) return@filter false
            val published = dates.getOrPut(show) {
                val podcast = shows.firstOrNull { SyncDocs.showKey(it.podcast_guid, it.feed_url) == show } ?: shows.firstOrNull { "url:${it.feed_url}" == show }
                podcast?.let { p -> db.episodeQueries.publishedOf(p.id).awaitAsList().associate { it.guid to it.published_at } }.orEmpty()
            }[guid]
            published != null && published <= mark.before && (mark.from == null || published >= mark.from)
        }.keys
    }

    private class Mark(val before: Long, val from: Long?, val t: Long)

    override suspend fun snapshot(): Map<String, SyncEntry> = withContext(ioDispatcher) {
        val since = nowSeconds() - KEPT.inWholeSeconds
        buildMap {
            for (p in db.podcastQueries.selectAll().awaitAsList()) {
                val before = p.played_through ?: continue
                val show = SyncDocs.showKey(p.podcast_guid, p.feed_url)
                val at = p.played_through_at ?: 0
                put(SyncDocs.playedThroughKey(show), SyncEntry(at * 1000, d = SyncDocs.playedThrough(show, before, at, p.played_through_from)))
            }
            val rows = db.episodeQueries.progressForSync(since).awaitAsList()
            // The newest listened to are never folded into "heard up to": it does not say they were played.
            val recent = rows.filter { it.played == 1L && it.last_played_at != null }
                .sortedByDescending { maxOf(it.last_played_at ?: 0, it.played_at ?: 0) }.take(RECENT).mapTo(mutableSetOf()) { it.podcast_guid to it.feed_url to it.guid }
            for (r in rows) {
                val show = SyncDocs.showKey(r.podcast_guid, r.feed_url)
                val heard = maxOf(r.last_played_at ?: 0, r.played_at ?: 0)
                // Heard by "heard up to": that one entry says it.
                val covered = (r.podcast_guid to r.feed_url to r.guid) !in recent && r.played == 1L && r.position_ms == 0L && r.published_at != null && r.played_through != null &&
                    r.published_at <= r.played_through && (r.played_through_from == null || r.published_at >= r.played_through_from) &&
                    (r.played_at ?: 0) <= (r.played_through_at ?: 0)
                if (!covered && (heard > 0 || r.position_ms > 0)) {
                    put(SyncDocs.progressKey(show, r.guid), SyncEntry(heard * 1000, d = SyncDocs.progress(show, r.guid, r.position_ms, r.played == 1L, heard, r.last_played_at)))
                }
                r.favorite_at?.let { at -> put(SyncDocs.favoriteKey(show, r.guid), SyncEntry(at * 1000, d = SyncDocs.favorite(show, r.guid, at))) }
            }
        }
    }

    override suspend fun apply(entries: Map<String, SyncEntry>): Set<String> {
        catchUp?.fetchFor(entries.mapNotNull { (key, e) ->
            if (!key.startsWith("pos\n") || e.del) return@mapNotNull null
            FeedCatchUp.Wanted(e.d.text("show") ?: return@mapNotNull null, e.d.text("guid") ?: return@mapNotNull null, e.t)
        })
        val shows = withContext(ioDispatcher) { db.podcastQueries.selectAll().awaitAsList() }
        val done = mutableSetOf<String>()
        // "Heard up to" first: an episode's own entry, newer, wins after it — "unheard" on one after "all" elsewhere.
        for ((key, e) in entries.entries.sortedBy { if (it.key.startsWith(UPTO)) 0 else 1 }) {
            if (key.startsWith(UPTO)) {
                val show = e.d.text("show")
                val before = e.d.long("before")
                val podcast = show?.let { s -> shows.firstOrNull { SyncDocs.showKey(it.podcast_guid, it.feed_url) == s } ?: shows.firstOrNull { "url:${it.feed_url}" == s } }
                when {
                    show == null || before == null || e.del -> done += key
                    // A show not subscribed here: next time.
                    podcast != null -> { episodes.applyPlayedThrough(podcast.id, before, e.d.long("from"), e.d.long("at")?.coerceAtLeast(0) ?: (e.t / 1000)); done += key }
                }
                continue
            }
            val show = e.d.text("show")
            val guid = e.d.text("guid")
            // Unreadable: nothing to wait for.
            if (show == null || guid == null) { done += key; continue }
            // A show not subscribed here, an episode its feed has not brought yet: next time.
            val podcast = shows.firstOrNull { SyncDocs.showKey(it.podcast_guid, it.feed_url) == show }
                ?: shows.firstOrNull { "url:${it.feed_url}" == show }
                ?: continue
            val id = withContext(ioDispatcher) { db.episodeQueries.selectByGuid(podcast.id, guid).awaitAsOneOrNull()?.id } ?: continue
            when {
                key.startsWith("fav\n") -> episodes.applyFavorite(id, if (e.del) null else e.d.long("at"))
                key.startsWith("pos\n") -> {
                    if (playing(id)) continue
                    // Never a place before the start (audit 2026-10-22, M-04).
                    val pos = (e.d.long("pos") ?: 0).coerceAtLeast(0)
                    val played = e.d.bool("played") ?: false
                    episodes.applyProgress(id, pos, played, e.d.long("at")?.coerceAtLeast(0) ?: (e.t / 1000), e.d.long("listened"))
                    // The player follows as an aside: its failing leaves the entry applied (M-06).
                    runCatching { adopt(id, pos, played) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                }
            }
            done += key
        }
        return done
    }

    /**
     * An episode's entry where its show's feed was read here, without an error, after the entry was
     * made — and the episode is not here: the feed no longer carries it (many keep only their newest
     * episodes; the other device had it from before). And whatever is about a show not followed here:
     * the subscriptions were applied before this document.
     */
    override suspend fun beyondReach(entries: Map<String, SyncEntry>): Set<String> {
        val shows = withContext(ioDispatcher) { db.podcastQueries.selectAll().awaitAsList() }
        return entries.filter { (key, e) ->
            val show = e.d.text("show") ?: return@filter false
            val podcast = shows.firstOrNull { SyncDocs.showKey(it.podcast_guid, it.feed_url) == show }
                ?: shows.firstOrNull { "url:${it.feed_url}" == show }
                ?: return@filter true
            if (key.startsWith(UPTO)) return@filter false
            val guid = e.d.text("guid") ?: return@filter false
            val readAfter = podcast.last_error == null && (podcast.last_checked_at ?: 0) * 1000 > e.t
            readAfter && withContext(ioDispatcher) { db.episodeQueries.selectByGuid(podcast.id, guid).awaitAsOneOrNull() } == null
        }.keys
    }

    companion object {
        val KEPT = 90.days
        private const val UPTO = "upto\n"
        /** Listened to, the newest this many keep an entry of their own ("Recently played" shows five). */
        private const val RECENT = 20
    }
}
