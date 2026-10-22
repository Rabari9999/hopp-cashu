package space.sovereigncast.hopp.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.db.HoppDatabase
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
) : SyncedDocument {
    override val name = SyncDocs.PROGRESS

    override fun expired(key: String, entry: SyncEntry, nowMs: Long): Boolean =
        key.startsWith("pos\n") && entry.t < nowMs - KEPT.inWholeMilliseconds

    override suspend fun snapshot(): Map<String, SyncEntry> = withContext(Dispatchers.IO) {
        val since = nowSeconds() - KEPT.inWholeSeconds
        buildMap {
            for (r in db.episodeQueries.progressForSync(since).executeAsList()) {
                val show = SyncDocs.showKey(r.podcast_guid, r.feed_url)
                val heard = maxOf(r.last_played_at ?: 0, r.played_at ?: 0)
                if (heard > 0 || r.position_ms > 0) {
                    put(SyncDocs.progressKey(show, r.guid), SyncEntry(heard * 1000, d = SyncDocs.progress(show, r.guid, r.position_ms, r.played == 1L, heard)))
                }
                r.favorite_at?.let { at -> put(SyncDocs.favoriteKey(show, r.guid), SyncEntry(at * 1000, d = SyncDocs.favorite(show, r.guid, at))) }
            }
        }
    }

    override suspend fun apply(entries: Map<String, SyncEntry>): Set<String> {
        val shows = withContext(Dispatchers.IO) { db.podcastQueries.selectAll().executeAsList() }
        val done = mutableSetOf<String>()
        for ((key, e) in entries) {
            val show = e.d.text("show")
            val guid = e.d.text("guid")
            // Unreadable: nothing to wait for.
            if (show == null || guid == null) { done += key; continue }
            // A show not subscribed here, an episode its feed has not brought yet: next time.
            val podcast = shows.firstOrNull { SyncDocs.showKey(it.podcast_guid, it.feed_url) == show }
                ?: shows.firstOrNull { "url:${it.feed_url}" == show }
                ?: continue
            val id = withContext(Dispatchers.IO) { db.episodeQueries.selectByGuid(podcast.id, guid).executeAsOneOrNull()?.id } ?: continue
            when {
                key.startsWith("fav\n") -> episodes.applyFavorite(id, if (e.del) null else e.d.long("at"))
                key.startsWith("pos\n") -> {
                    if (playing(id)) continue
                    // Never a place before the start (audit 2026-10-22, M-04).
                    val pos = (e.d.long("pos") ?: 0).coerceAtLeast(0)
                    val played = e.d.bool("played") ?: false
                    episodes.applyProgress(id, pos, played, e.d.long("at")?.coerceAtLeast(0) ?: (e.t / 1000))
                    // The player follows as an aside: its failing leaves the entry applied (M-06).
                    runCatching { adopt(id, pos, played) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                }
            }
            done += key
        }
        return done
    }

    companion object {
        val KEPT = 90.days
    }
}
