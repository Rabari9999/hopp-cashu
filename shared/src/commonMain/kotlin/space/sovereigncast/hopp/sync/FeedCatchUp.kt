package space.sovereigncast.hopp.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.platform.ioDispatcher
import kotlin.time.Duration.Companion.days

/**
 * A show's feed read at once for an entry about one of its episodes this device has not got yet
 * (maintainer 2026-11-09): the episode started on the phone was out a moment ago, the tablet's feed
 * last read before — and what plays now, and where, waited there for the next look at the feed,
 * up to hours away. Only where the feed was read before the entry was made, so a feed that no
 * longer carries the episode is not asked again each round; only for entries of the last
 * [RECENT], and at most [AT_ONCE] feeds a round.
 */
class FeedCatchUp(
    private val db: HoppDatabase,
    private val nowMs: () -> Long,
    /** Reads the feed of podcast id; its failing is the next round's to try again. */
    private val fetch: suspend (Long) -> Unit,
) {
    /** One episode an entry wants: its show's key, its guid, and when the entry was made. */
    data class Wanted(val show: String, val guid: String, val atMs: Long)

    /** The feeds [wanted] need, read; whether any was. */
    suspend fun fetchFor(wanted: List<Wanted>): Boolean {
        val since = nowMs() - RECENT.inWholeMilliseconds
        val recent = wanted.filter { it.atMs >= since }
        if (recent.isEmpty()) return false
        val ids = withContext(ioDispatcher) {
            val shows = db.podcastQueries.selectAll().awaitAsList()
            recent.mapNotNull { w ->
                val podcast = shows.firstOrNull { SyncDocs.showKey(it.podcast_guid, it.feed_url) == w.show }
                    ?: shows.firstOrNull { "url:${it.feed_url}" == w.show }
                    ?: return@mapNotNull null
                // Read after the entry, and read well: the episode is not in it. A feed that failed is asked again.
                if (podcast.last_error == null && (podcast.last_checked_at ?: 0) * 1000 >= w.atMs) return@mapNotNull null
                if (db.episodeQueries.selectByGuid(podcast.id, w.guid).awaitAsOneOrNull() != null) return@mapNotNull null
                podcast.id
            }.distinct().take(AT_ONCE)
        }
        for (id in ids) runCatching { fetch(id) }.onFailure { if (it is CancellationException) throw it }
        return ids.isNotEmpty()
    }

    companion object {
        val RECENT = 2.days
        const val AT_ONCE = 3
    }
}
