package space.sovereigncast.hopp.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.feed.Chapter
import space.sovereigncast.hopp.feed.ChaptersParser
import space.sovereigncast.hopp.feed.FeedParser
import space.sovereigncast.hopp.model.Episode
import space.sovereigncast.hopp.model.Feed
import space.sovereigncast.hopp.model.Podcast
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.net.FetchResult

/** Seconds since the epoch, injectable for tests. */
fun interface Clock { fun now(): Long }

/** A subscription as the UI lists it. */
data class Show(
    val id: Long,
    val feedUrl: String,
    val title: String,
    val author: String?,
    val imageUrl: String?,
    val lastError: String?,
)

/** An episode as the UI lists it — the parsed feed data stays in the database
 *  until it is needed ([EpisodeRepository.details]). */
data class EpisodeItem(
    val id: Long,
    val podcastId: Long,
    val guid: String,
    val title: String,
    val publishedAt: Long?,
    val durationSeconds: Long?,
    val imageUrl: String?,
    val enclosureUrl: String?,
    val chaptersUrl: String?,
    val positionMs: Long,
    val played: Boolean,
    val inInbox: Boolean,
    val podcastTitle: String? = null,
    val podcastImageUrl: String? = null,
)

internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

sealed interface SubscribeResult {
    data class Subscribed(val podcastId: Long, val title: String) : SubscribeResult
    data class AlreadySubscribed(val podcastId: Long) : SubscribeResult
    data class Failed(val reason: String) : SubscribeResult
}

data class RefreshSummary(val checked: Int, val changed: Int, val newEpisodes: Int, val failed: Int)

class PodcastRepository(
    private val db: HoppDatabase,
    private val fetcher: FeedFetcher,
    private val clock: Clock,
) {
    private val q = db.podcastQueries
    private val eq = db.episodeQueries

    val shows: Flow<List<Show>> = q.selectAll().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map { Show(it.id, it.feed_url, it.title, it.author, it.image_url, it.last_error) }
    }

    fun show(id: Long): Flow<Show?> = q.selectById(id).asFlow().mapToOneOrNull(Dispatchers.IO).map { it?.let { r ->
        Show(r.id, r.feed_url, r.title, r.author, r.image_url, r.last_error)
    } }

    suspend fun podcastMeta(id: Long): Podcast? = withContext(Dispatchers.IO) {
        q.selectById(id).executeAsOneOrNull()?.let { json.decodeFromString(Podcast.serializer(), it.meta_json) }
    }

    /** Fetch, parse, store. The newest episode lands in "New"; the back
     *  catalogue does not flood it. */
    suspend fun subscribe(input: String): SubscribeResult {
        val url = FeedFetcher.normalize(input) ?: return SubscribeResult.Failed("not a feed address")
        withContext(Dispatchers.IO) { q.selectByFeedUrl(url).executeAsOneOrNull() }?.let {
            return SubscribeResult.AlreadySubscribed(it.id)
        }
        val fetched = try {
            fetcher.fetch(url) as? FetchResult.Fetched ?: return SubscribeResult.Failed("empty response")
        } catch (e: Exception) {
            return SubscribeResult.Failed(e.message ?: "network error")
        }
        val feedUrl = fetched.movedTo ?: url
        val feed = try {
            withContext(Dispatchers.Default) { FeedParser.parse(fetched.bytes) }
        } catch (e: Exception) {
            return SubscribeResult.Failed(e.message ?: "not a podcast feed")
        }
        if (feed.episodes.isEmpty() && feed.podcast.title == null) return SubscribeResult.Failed("not a podcast feed")
        withContext(Dispatchers.IO) { q.selectByFeedUrl(feedUrl).executeAsOneOrNull() }?.let {
            return SubscribeResult.AlreadySubscribed(it.id)
        }
        val now = clock.now()
        val id = withContext(Dispatchers.IO) {
            db.transactionWithResult {
                val p = feed.podcast
                q.insert(feedUrl, p.podcastGuid, p.title ?: feedUrl, p.author, p.imageUrl,
                    json.encodeToString(Podcast.serializer(), p), fetched.etag, fetched.lastModified, now, now)
                val id = q.lastInsertId().executeAsOne()
                val newest = feed.episodes.maxByOrNull { it.publishedAt ?: 0 }?.guid
                for (e in feed.episodes) insertEpisode(id, e, inInbox = e.guid == newest, now)
                id
            }
        }
        return SubscribeResult.Subscribed(id, feed.podcast.title ?: feedUrl)
    }

    /** Conditional fetch; new items go to "New", known ones keep the
     *  listener's state but take the feed's corrections. */
    suspend fun refresh(id: Long): Int {
        val row = withContext(Dispatchers.IO) { q.selectById(id).executeAsOneOrNull() } ?: return 0
        val now = clock.now()
        val result = try {
            fetcher.fetch(row.feed_url, row.etag, row.last_modified)
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { q.markChecked(null, null, now, e.message ?: "network error", id) }
            throw e
        }
        if (result !is FetchResult.Fetched) {
            withContext(Dispatchers.IO) { q.markChecked(null, null, now, null, id) }
            return 0
        }
        val feed: Feed = withContext(Dispatchers.Default) { FeedParser.parse(result.bytes) }
        return withContext(Dispatchers.IO) {
            db.transactionWithResult {
                result.movedTo?.let { moved -> if (q.selectByFeedUrl(moved).executeAsOneOrNull() == null) q.updateFeedUrl(moved, id) }
                val p = feed.podcast
                q.updateFromFeed(p.podcastGuid, p.title ?: row.title, p.author, p.imageUrl,
                    json.encodeToString(Podcast.serializer(), p), result.etag, result.lastModified, now, id)
                val known = eq.guidsOfPodcast(id).executeAsList().toSet()
                var added = 0
                for (e in feed.episodes) {
                    if (e.guid in known) {
                        eq.updateFromFeed(e.title, e.publishedAt, e.durationSeconds, e.imageUrl, e.enclosure?.url,
                            e.chaptersUrl, json.encodeToString(Episode.serializer(), e), id, e.guid)
                    } else {
                        insertEpisode(id, e, inInbox = true, now); added++
                    }
                }
                added
            }
        }
    }

    suspend fun refreshAll(parallel: Int = 4): RefreshSummary = coroutineScope {
        val ids = withContext(Dispatchers.IO) { q.selectAll().executeAsList().map { it.id } }
        val gate = Semaphore(parallel)
        val results = ids.map { id ->
            async { gate.withPermit { runCatching { refresh(id) } } }
        }.awaitAll()
        RefreshSummary(
            checked = ids.size,
            changed = results.count { (it.getOrNull() ?: 0) > 0 },
            newEpisodes = results.sumOf { it.getOrNull() ?: 0 },
            failed = results.count { it.isFailure },
        )
    }

    suspend fun unsubscribe(id: Long) = withContext(Dispatchers.IO) {
        db.transaction {
            db.chapterCacheQueries.deleteByPodcast(id)
            db.queueQueries.deleteByPodcast(id)
            eq.deleteByPodcast(id)
            q.delete(id)
        }
    }

    suspend fun exportOpml(): String = withContext(Dispatchers.IO) {
        Opml.export(q.selectAll().executeAsList().map { Opml.Entry(it.feed_url, it.title) })
    }

    /** Subscribes to every feed of an OPML file that is not subscribed yet. */
    suspend fun importOpml(text: String, parallel: Int = 4): List<Pair<String, SubscribeResult>> = coroutineScope {
        val gate = Semaphore(parallel)
        Opml.parse(text).map { entry ->
            async { gate.withPermit { entry.feedUrl to subscribe(entry.feedUrl) } }
        }.awaitAll()
    }

    private fun insertEpisode(podcastId: Long, e: Episode, inInbox: Boolean, now: Long) {
        eq.insertIfNew(podcastId, e.guid, e.title, e.publishedAt, e.durationSeconds, e.imageUrl, e.enclosure?.url,
            e.chaptersUrl, json.encodeToString(Episode.serializer(), e), if (inInbox) 1 else 0, now)
    }
}

class EpisodeRepository(
    private val db: HoppDatabase,
    private val http: HttpClient,
    private val clock: Clock,
) {
    private val eq = db.episodeQueries

    val inbox: Flow<List<EpisodeItem>> = eq.selectInbox().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map { r ->
            EpisodeItem(r.id, r.podcast_id, r.guid, r.title ?: "", r.published_at, r.duration_seconds, r.image_url,
                r.enclosure_url, r.chapters_url, r.position_ms, r.played == 1L, r.in_inbox == 1L, r.podcast_title, r.podcast_image_url)
        }
    }

    val inProgress: Flow<List<EpisodeItem>> = eq.selectInProgress().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map { r ->
            EpisodeItem(r.id, r.podcast_id, r.guid, r.title ?: "", r.published_at, r.duration_seconds, r.image_url,
                r.enclosure_url, r.chapters_url, r.position_ms, r.played == 1L, r.in_inbox == 1L, r.podcast_title, r.podcast_image_url)
        }
    }

    fun ofPodcast(podcastId: Long): Flow<List<EpisodeItem>> =
        eq.selectByPodcast(podcastId).asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toItem() } }

    suspend fun item(id: Long): EpisodeItem? = withContext(Dispatchers.IO) { eq.selectById(id).executeAsOneOrNull()?.toItem() }

    suspend fun details(id: Long): Episode? = withContext(Dispatchers.IO) {
        eq.selectById(id).executeAsOneOrNull()?.let { json.decodeFromString(Episode.serializer(), it.meta_json) }
    }

    suspend fun savePosition(id: Long, positionMs: Long) = withContext(Dispatchers.IO) { eq.savePosition(positionMs, clock.now(), id) }

    suspend fun setPlayed(id: Long, played: Boolean) = withContext(Dispatchers.IO) {
        if (played) eq.markPlayed(id) else eq.markUnplayed(id)
    }

    suspend fun dismiss(id: Long) = withContext(Dispatchers.IO) { eq.setInbox(0, id) }
    suspend fun clearInbox() = withContext(Dispatchers.IO) { eq.clearInbox() }

    /** Chapters from the cache, else fetched and cached (offline after the first play). */
    suspend fun chapters(item: EpisodeItem): List<Chapter> {
        val url = item.chaptersUrl ?: return emptyList()
        withContext(Dispatchers.IO) { db.chapterCacheQueries.select(item.id).executeAsOneOrNull() }?.let {
            return runCatching { ChaptersParser.parse(it) }.getOrDefault(emptyList())
        }
        val text = http.get(url).bodyAsText()
        val chapters = withContext(Dispatchers.Default) { ChaptersParser.parse(text) }
        withContext(Dispatchers.IO) { db.chapterCacheQueries.upsert(item.id, text, clock.now()) }
        return chapters
    }

    private fun space.sovereigncast.hopp.db.Episode.toItem() = EpisodeItem(
        id, podcast_id, guid, title ?: "", published_at, duration_seconds, image_url, enclosure_url, chapters_url,
        position_ms, played == 1L, in_inbox == 1L,
    )
}
