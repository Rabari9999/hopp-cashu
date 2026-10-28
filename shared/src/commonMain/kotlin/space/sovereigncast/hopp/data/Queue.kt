package space.sovereigncast.hopp.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.platform.ioDispatcher
import space.sovereigncast.hopp.sync.SyncDocs
import space.sovereigncast.hopp.sync.SyncJournal

/** "Up next" (ADR 0012): play next, play last, reorder; the player takes the
 *  first one when an episode ends. The episode playing is never in it. */
class QueueRepository(
    private val db: HoppDatabase,
    /** What the listener puts in, takes out and moves, noted for sync (ADR 0021, 5). An
     *  episode added by a finished download is not: that happened on this device only. */
    private val journal: SyncJournal = SyncJournal.Off,
) {
    private val qq = db.queueQueries

    private fun note(episodeIds: Collection<Long>) {
        if (!journal.enabled) return
        val keys = qq.selectKeys().executeAsList().associate { it.episode_id to it.sort_key }
        for (id in episodeIds) {
            val r = db.episodeQueries.syncKeysOf(id).executeAsOneOrNull() ?: continue
            val show = SyncDocs.showKey(r.podcast_guid, r.feed_url)
            val sort = keys[id]
            journal.record(SyncDocs.QUEUE, SyncDocs.queueKey(show, r.guid), SyncDocs.queued(show, r.guid, sort ?: 0), deleted = sort == null)
        }
    }

    /** "Up next" from another device (sync): the episode at [sortKey], or out of it; not noted again. */
    suspend fun applySynced(episodeId: Long, sortKey: Long?) = withContext(ioDispatcher) {
        if (sortKey == null) qq.remove(episodeId) else qq.put(episodeId, sortKey)
    }

    val items: Flow<List<EpisodeItem>> = qq.selectItems().asFlow().mapToList(ioDispatcher).map { rows ->
        rows.map { r ->
            episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url,
                r.chapters_url, r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url, r.favorite_at, r.enclosure_length)
        }
    }

    suspend fun playNext(episodeId: Long) = withContext(ioDispatcher) {
        val moved = db.transactionWithResult {
            qq.remove(episodeId)
            val renumbered = renumberAtTheEdge()
            qq.put(episodeId, qq.minKey().executeAsOne() - 1)
            renumbered
        }
        note(moved + episodeId)
    }

    suspend fun playLast(episodeId: Long) = withContext(ioDispatcher) {
        val moved = db.transactionWithResult {
            qq.remove(episodeId)
            val renumbered = renumberAtTheEdge()
            qq.put(episodeId, qq.maxKey().executeAsOne() + 1)
            renumbered
        }
        note(moved + episodeId)
    }

    /**
     * A place from sync at the edge ([SORT_MAX]): the queue numbered 0, 1, 2 … again, in the order it
     * has, before one goes before or after it — at the edge, two in a row got the same place and their
     * order fell to the episodes' names (delta audit 2 2026-10-22, ND-02). The episodes renumbered.
     */
    private fun renumberAtTheEdge(): List<Long> {
        if (qq.minKey().executeAsOne() > -SORT_MAX && qq.maxKey().executeAsOne() < SORT_MAX) return emptyList()
        val order = qq.selectAll().executeAsList()
        order.forEachIndexed { i, id -> qq.put(id, i.toLong()) }
        return order
    }

    suspend fun remove(episodeId: Long) = withContext(ioDispatcher) { qq.remove(episodeId); note(listOf(episodeId)) }

    /** The whole order at once, as the list looks after a drag. */
    suspend fun reorder(episodeIds: List<Long>) = withContext(ioDispatcher) {
        db.transaction { episodeIds.forEachIndexed { i, id -> qq.put(id, i.toLong()) } }
        note(episodeIds)
    }

    /** Takes the first one off the queue. */
    suspend fun pop(): Long? = withContext(ioDispatcher) {
        db.transactionWithResult { qq.first().executeAsOneOrNull()?.also { qq.remove(it) } }?.also { note(listOf(it)) }
    }

    suspend fun peek(): Long? = withContext(ioDispatcher) { qq.first().executeAsOneOrNull() }

    companion object {
        /** The farthest place in "Up next" — far beyond any real queue, and never near a Long's end
         *  (delta audit 2026-10-22, MN-06: a place from sync at Long.MAX_VALUE wrapped to the front). */
        const val SORT_MAX = 1_000_000_000_000_000L
    }
}
