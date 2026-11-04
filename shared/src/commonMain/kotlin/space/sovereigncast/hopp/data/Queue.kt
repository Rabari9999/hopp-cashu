package space.sovereigncast.hopp.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.platform.ioDispatcher
import space.sovereigncast.hopp.sync.SyncDocs
import space.sovereigncast.hopp.sync.SyncJournal

/** "Up next" (ADR 0012), since 2026-11-03 the playlist after the episode playing (Dennis, maintainer):
 *  play next, play last, reorder; the player takes the first one when an episode ends. The episode
 *  playing is never in it — it is the playlist's "Now". */
class QueueRepository(
    private val db: HoppDatabase,
    /** What the listener puts in, takes out and moves, noted for sync (ADR 0021, 5). An
     *  episode added by a finished download is not: that happened on this device only. */
    private val journal: SyncJournal = SyncJournal.Off,
) {
    private val qq = db.queueQueries

    private suspend fun note(episodeIds: Collection<Long>) {
        if (!journal.enabled) return
        val keys = qq.selectKeys().awaitAsList().associate { it.episode_id to it.sort_key }
        for (id in episodeIds) {
            val r = db.episodeQueries.syncKeysOf(id).awaitAsOneOrNull() ?: continue
            val show = SyncDocs.showKey(r.podcast_guid, r.feed_url)
            val sort = keys[id]
            journal.record(SyncDocs.QUEUE, SyncDocs.queueKey(show, r.guid), SyncDocs.queued(show, r.guid, sort ?: 0), deleted = sort == null)
        }
    }

    /**
     * New episodes a refresh found, to the end of "Up next", oldest first (maintainer 2026-11-03) — not one
     * heard, and not one sync knows already: in "Up next" from another device, or taken out of it there.
     * Noted for sync as of when the episode came out, so the other devices' later changes to it win
     * (one taken out on the phone overnight does not come back when the browser refreshes next morning).
     */
    suspend fun addNew(episodes: List<Pair<Long, Long?>>) = withContext(ioDispatcher) {
        val noted = mutableListOf<Long>()
        for ((id, publishedAt) in episodes) {
            val r = db.episodeQueries.syncKeysOf(id).awaitAsOneOrNull() ?: continue
            if (r.played == 1L) continue
            val key = SyncDocs.queueKey(SyncDocs.showKey(r.podcast_guid, r.feed_url), r.guid)
            if (journal.current(SyncDocs.QUEUE, key) != null) continue
            qq.addLast(id)
            noted += id
            if (journal.enabled) {
                val sort = qq.selectKeys().awaitAsList().firstOrNull { it.episode_id == id }?.sort_key ?: continue
                val show = SyncDocs.showKey(r.podcast_guid, r.feed_url)
                journal.record(SyncDocs.QUEUE, key, SyncDocs.queued(show, r.guid, sort), notify = false, atMs = (publishedAt ?: 0) * 1000)
            }
        }
        if (noted.isNotEmpty()) journal.changed(SyncDocs.QUEUE)
    }

    /**
     * What is in "Up next" here and sync knows nothing of — put there by a finished download before 0.4.0,
     * which was never noted (maintainer 2026-11-03) — noted now, as of the episode's release, so a change
     * made since on another device still wins. Once per start; nothing to do after the first.
     */
    suspend fun noteUnknownOnce() {
        if (unknownNoted || !journal.enabled) return
        unknownNoted = true
        // A device new to sync brings its queue in by the snapshot, where the relays have none.
        if (!journal.knows(SyncDocs.QUEUE)) return
        withContext(ioDispatcher) {
            var any = false
            for (q in qq.selectKeys().awaitAsList()) {
                val r = db.episodeQueries.syncKeysOf(q.episode_id).awaitAsOneOrNull() ?: continue
                val show = SyncDocs.showKey(r.podcast_guid, r.feed_url)
                val key = SyncDocs.queueKey(show, r.guid)
                if (journal.current(SyncDocs.QUEUE, key) != null) continue
                journal.record(SyncDocs.QUEUE, key, SyncDocs.queued(show, r.guid, q.sort_key), notify = false, atMs = (r.published_at ?: 0) * 1000)
                any = true
            }
            if (any) journal.changed(SyncDocs.QUEUE)
        }
    }
    private var unknownNoted = false

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
            qq.put(episodeId, qq.minKey().awaitAsOne() - 1)
            renumbered
        }
        note(moved + episodeId)
    }

    suspend fun playLast(episodeId: Long) = withContext(ioDispatcher) {
        val moved = db.transactionWithResult {
            qq.remove(episodeId)
            val renumbered = renumberAtTheEdge()
            qq.put(episodeId, qq.maxKey().awaitAsOne() + 1)
            renumbered
        }
        note(moved + episodeId)
    }

    /**
     * A place from sync at the edge ([SORT_MAX]): the queue numbered 0, 1, 2 … again, in the order it
     * has, before one goes before or after it — at the edge, two in a row got the same place and their
     * order fell to the episodes' names (delta audit 2 2026-10-22, ND-02). The episodes renumbered.
     */
    private suspend fun renumberAtTheEdge(): List<Long> {
        if (qq.minKey().awaitAsOne() > -SORT_MAX && qq.maxKey().awaitAsOne() < SORT_MAX) return emptyList()
        val order = qq.selectAll().awaitAsList()
        order.forEachIndexed { i, id -> qq.put(id, i.toLong()) }
        return order
    }

    suspend fun remove(episodeId: Long) = withContext(ioDispatcher) { qq.remove(episodeId); note(listOf(episodeId)) }

    /** Out of the playlist, and the order it was in — to [putBack] it (maintainer 2026-11-04). */
    suspend fun removeKeepingOrder(episodeId: Long): List<Long> = withContext(ioDispatcher) {
        val before = db.transactionWithResult { qq.selectAll().awaitAsList().also { qq.remove(episodeId) } }
        note(listOf(episodeId))
        before
    }

    /**
     * [episodeId] back where it was in [before] ("Undo"; maintainer 2026-11-04: it came back at the end).
     * What went since stays out; what came since keeps its place after the rest.
     */
    suspend fun putBack(episodeId: Long, before: List<Long>) = withContext(ioDispatcher) {
        val order = db.transactionWithResult {
            val now = qq.selectAll().awaitAsList()
            if (episodeId in now) return@transactionWithResult emptyList()
            (before.filter { it == episodeId || it in now } + now.filter { it !in before })
                .also { all -> all.forEachIndexed { i, id -> qq.put(id, i.toLong()) } }
        }
        note(order)
    }

    /** Everything out of the playlist at once — the episodes keep their progress. */
    suspend fun clear() = withContext(ioDispatcher) {
        val all = qq.selectAll().awaitAsList()
        db.transaction { all.forEach { qq.remove(it) } }
        note(all)
    }

    /**
     * Once, as "Continue listening" goes (Dennis, maintainer 2026-11-03): the episodes begun and not
     * finished that were not in "Up next" yet — [inProgress], newest first — to the playlist's top in that
     * order, so none is lost; not [except], the one the player has, which is the playlist's "Now".
     */
    suspend fun adoptInProgress(inProgress: List<EpisodeItem>, except: Long?) = withContext(ioDispatcher) {
        val queued = qq.selectAll().awaitAsList().toSet()
        val adopt = inProgress.filter { !it.played && it.positionMs > 0 && it.id != except && it.id !in queued }.distinctBy { it.id }.take(ADOPT_MAX)
        if (adopt.isEmpty()) return@withContext
        db.transaction {
            var key = qq.minKey().awaitAsOne()
            adopt.asReversed().forEach { key -= 1; qq.put(it.id, key) }
        }
        note(adopt.map { it.id })
    }

    /** The whole order at once, as the list looks after a drag. */
    suspend fun reorder(episodeIds: List<Long>) = withContext(ioDispatcher) {
        db.transaction { episodeIds.forEachIndexed { i, id -> qq.put(id, i.toLong()) } }
        note(episodeIds)
    }

    /** Takes the first one off the queue. */
    suspend fun pop(): Long? = withContext(ioDispatcher) {
        db.transactionWithResult { qq.first().awaitAsOneOrNull()?.also { qq.remove(it) } }?.also { note(listOf(it)) }
    }

    suspend fun peek(): Long? = withContext(ioDispatcher) { qq.first().awaitAsOneOrNull() }

    companion object {
        /** The farthest place in "Up next" — far beyond any real queue, and never near a Long's end
         *  (delta audit 2026-10-22, MN-06: a place from sync at Long.MAX_VALUE wrapped to the front). */
        const val SORT_MAX = 1_000_000_000_000_000L
        /** As many as "Continue listening" showed. */
        const val ADOPT_MAX = 20
    }
}
