package space.sovereigncast.hopp.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.db.HoppDatabase

/** "Up next" (ADR 0012): play next, play last, reorder; the player takes the
 *  first one when an episode ends. The episode playing is never in it. */
class QueueRepository(private val db: HoppDatabase) {
    private val qq = db.queueQueries

    val items: Flow<List<EpisodeItem>> = qq.selectItems().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map { r ->
            episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url,
                r.chapters_url, r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url)
        }
    }

    suspend fun playNext(episodeId: Long) = withContext(Dispatchers.IO) {
        db.transaction { qq.remove(episodeId); qq.put(episodeId, qq.minKey().executeAsOne() - 1) }
    }

    suspend fun playLast(episodeId: Long) = withContext(Dispatchers.IO) {
        db.transaction { qq.remove(episodeId); qq.put(episodeId, qq.maxKey().executeAsOne() + 1) }
    }

    suspend fun remove(episodeId: Long) = withContext(Dispatchers.IO) { qq.remove(episodeId) }

    /** The whole order at once, as the list looks after a drag. */
    suspend fun reorder(episodeIds: List<Long>) = withContext(Dispatchers.IO) {
        db.transaction { episodeIds.forEachIndexed { i, id -> qq.put(id, i.toLong()) } }
    }

    /** Takes the first one off the queue. */
    suspend fun pop(): Long? = withContext(Dispatchers.IO) {
        db.transactionWithResult { qq.first().executeAsOneOrNull()?.also { qq.remove(it) } }
    }

    suspend fun peek(): Long? = withContext(Dispatchers.IO) { qq.first().executeAsOneOrNull() }
}
