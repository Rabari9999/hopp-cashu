package space.sovereigncast.hopp.data

import io.ktor.http.isSuccess
import space.sovereigncast.hopp.net.getLimited
import space.sovereigncast.hopp.net.Limits
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
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
    val autoDownload: Boolean = true,
    val playbackSpeed: Float? = null,
    val podcastGuid: String? = null,
)

/** A show's numbers in the library: all its episodes, and the new ones among them. */
data class ShowCounts(val episodes: Int, val new: Int)

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
    val downloadState: Int = DownloadState.NONE,
    val downloadPath: String? = null,
    /** A favourite of the listener's (device feedback 2026-10-11). */
    val favorite: Boolean = false,
) {
    /** What the player opens: the file once it is complete, else the stream. */
    val playableUrl: String? get() = downloadPath?.takeIf { downloadState == DownloadState.DONE }?.let { "file://$it" } ?: enclosureUrl
}

/** One mapping for every query that selects `episode.*` (plus the show's
 *  title and image where joined) — SQLDelight gives each its own type. */
internal fun episodeItem(
    id: Long, podcastId: Long, guid: String, title: String?, publishedAt: Long?, durationSeconds: Long?, imageUrl: String?,
    enclosureUrl: String?, chaptersUrl: String?, positionMs: Long, played: Long, inInbox: Long, downloadState: Long,
    downloadPath: String?, podcastTitle: String? = null, podcastImageUrl: String? = null, favoriteAt: Long? = null,
) = EpisodeItem(
    id, podcastId, guid, title ?: "", publishedAt, durationSeconds, imageUrl, enclosureUrl, chaptersUrl, positionMs,
    played == 1L, inInbox == 1L, podcastTitle, podcastImageUrl, downloadState.toInt(), downloadPath, favoriteAt != null,
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
    private val files: DownloadFiles? = null,
    private val downloads: DownloadScheduler = DownloadScheduler.None,
) {
    private val q = db.podcastQueries
    private val eq = db.episodeQueries

    /** When any show was last refreshed, epoch seconds; null before the first. */
    val lastChecked: Flow<Long?> = q.lastChecked().asFlow().mapToOneOrNull(Dispatchers.IO).map { it?.last }

    val shows: Flow<List<Show>> = q.selectAll().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map { Show(it.id, it.feed_url, it.title, it.author, it.image_url, it.last_error, it.auto_download == 1L, it.playback_speed?.toFloat(), it.podcast_guid) }
    }

    fun show(id: Long): Flow<Show?> = q.selectById(id).asFlow().mapToOneOrNull(Dispatchers.IO).map { it?.let { r ->
        Show(r.id, r.feed_url, r.title, r.author, r.image_url, r.last_error, r.auto_download == 1L, r.playback_speed?.toFloat(), r.podcast_guid)
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
        return subscribe(feedUrl, feed, fetched.etag, fetched.lastModified)
    }

    /** Subscribes to a feed that is already fetched and parsed — the show page
     *  before subscribing has done both, and doing them twice was the slow
     *  part of subscribing (device feedback 2026-10-08). */
    suspend fun subscribe(feedUrl: String, feed: Feed, etag: String?, lastModified: String?): SubscribeResult {
        withContext(Dispatchers.IO) { q.selectByFeedUrl(feedUrl).executeAsOneOrNull() }?.let {
            return SubscribeResult.AlreadySubscribed(it.id)
        }
        val now = clock.now()
        val id = withContext(Dispatchers.IO) {
            db.transactionWithResult {
                val p = feed.podcast
                q.insert(feedUrl, p.podcastGuid, p.title ?: feedUrl, p.author, p.imageUrl,
                    json.encodeToString(Podcast.serializer(), p), etag, lastModified, now, now)
                val id = q.lastInsertId().executeAsOne()
                val newest = feed.episodes.maxByOrNull { it.publishedAt ?: 0 }?.guid
                for (e in feed.episodes) insertEpisode(id, e, inInbox = e.guid == newest, now, autoDownload = true)
                id
            }
        }
        downloads.schedule(manual = false)
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
        val added = withContext(Dispatchers.IO) {
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
                        insertEpisode(id, e, inInbox = true, now, autoDownload = row.auto_download == 1L); added++
                    }
                }
                added
            }
        }
        if (added > 0 && row.auto_download == 1L) downloads.schedule(manual = false)
        return added
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
        eq.downloadPathsOfPodcast(id).executeAsList().forEach { path -> path?.let { files?.delete(it) } }
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

    suspend fun setSpeed(id: Long, speed: Float) = withContext(Dispatchers.IO) { q.setSpeed(speed.toDouble(), id) }
    suspend fun setAutoDownload(id: Long, on: Boolean) = withContext(Dispatchers.IO) { q.setAutoDownload(if (on) 1 else 0, id) }

    /** New in "New" means: downloaded too, on Wi-Fi, if the show wants it (ADR 0012). */
    private fun insertEpisode(podcastId: Long, e: Episode, inInbox: Boolean, now: Long, autoDownload: Boolean) {
        val queued = inInbox && autoDownload && e.enclosure?.url != null
        eq.insertIfNew(podcastId, e.guid, e.title, e.publishedAt, e.durationSeconds, e.imageUrl, e.enclosure?.url,
            e.chaptersUrl, json.encodeToString(Episode.serializer(), e), if (inInbox) 1 else 0, now,
            if (queued) DownloadState.QUEUED.toLong() else DownloadState.NONE.toLong())
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
            episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url,
                r.chapters_url, r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url, r.favorite_at)
        }
    }

    /** Per show: how many episodes it has, and how many of them are new — the library's numbers. */
    val countsPerShow: Flow<Map<Long, ShowCounts>> = eq.countsPerPodcast().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.associate { it.podcast_id to ShowCounts(it.total.toInt(), (it.fresh ?: 0L).toInt()) }
    }

    val inProgress: Flow<List<EpisodeItem>> = eq.selectInProgress().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map { r ->
            episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url,
                r.chapters_url, r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url, r.favorite_at)
        }
    }

    fun recentlyPlayed(limit: Long): Flow<List<EpisodeItem>> = eq.selectRecentlyPlayed(limit).asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map { r ->
            episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url,
                r.chapters_url, r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url, r.favorite_at)
        }
    }

    fun ofPodcast(podcastId: Long): Flow<List<EpisodeItem>> =
        eq.selectByPodcast(podcastId).asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toItem() } }

    /** The newest [limit] episodes of a show (paging, device feedback 2026-10-08). */
    fun ofPodcastPage(podcastId: Long, limit: Long): Flow<List<EpisodeItem>> =
        eq.selectByPodcastPage(podcastId, limit).asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toItem() } }

    fun countOfPodcast(podcastId: Long): Flow<Long> = eq.countByPodcast(podcastId).asFlow().mapToOne(Dispatchers.IO)

    /** One episode, live — its page follows played, position and download. */
    fun itemFlow(id: Long): Flow<EpisodeItem?> = eq.selectById(id).asFlow().mapToOneOrNull(Dispatchers.IO).map { it?.toItem() }

    suspend fun item(id: Long): EpisodeItem? = withContext(Dispatchers.IO) { eq.selectById(id).executeAsOneOrNull()?.toItem() }

    /** An episode of a show by its GUID — what a shared link names. */
    suspend fun byGuid(podcastId: Long, guid: String): EpisodeItem? =
        withContext(Dispatchers.IO) { eq.selectByGuid(podcastId, guid).executeAsOneOrNull()?.id }?.let { item(it) }

    suspend fun details(id: Long): Episode? = withContext(Dispatchers.IO) {
        eq.selectById(id).executeAsOneOrNull()?.let { json.decodeFromString(Episode.serializer(), it.meta_json) }
    }

    suspend fun savePosition(id: Long, positionMs: Long) = withContext(Dispatchers.IO) { eq.savePosition(positionMs, clock.now(), id) }

    suspend fun setPlayed(id: Long, played: Boolean) = withContext(Dispatchers.IO) {
        if (played) eq.markPlayed(clock.now(), id) else eq.markUnplayed(id)
    }

    suspend fun dismiss(id: Long) = withContext(Dispatchers.IO) { eq.setInbox(0, id) }
    suspend fun clearInbox() = withContext(Dispatchers.IO) { eq.clearInbox() }

    /** Chapters from the cache, else fetched and cached (offline after the first play). */
    suspend fun chapters(item: EpisodeItem): List<Chapter> {
        val url = item.chaptersUrl ?: return emptyList()
        withContext(Dispatchers.IO) { db.chapterCacheQueries.select(item.id).executeAsOneOrNull() }?.let {
            return runCatching { ChaptersParser.parse(it) }.getOrDefault(emptyList())
        }
        val response = http.getLimited(url, Limits.CHAPTERS)
        // An error page is no chapter file, and nothing to keep.
        if (!response.status.isSuccess()) throw IllegalStateException("chapters: HTTP ${response.status.value}")
        val text = response.text()
        val chapters = withContext(Dispatchers.Default) { ChaptersParser.parse(text) }
        withContext(Dispatchers.IO) { db.chapterCacheQueries.upsert(item.id, text, clock.now()) }
        return chapters
    }

    /** The favourites, newest first (device feedback 2026-10-11). */
    /** Per show the newest episode not heard, not started and not new (Home, device feedback 2026-10-12). */
    val unheardPerShow: Flow<List<EpisodeItem>> = eq.selectUnheardPerShow().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.distinctBy { it.podcast_id }.map { r ->
            episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url,
                r.chapters_url, r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url, r.favorite_at)
        }
    }

    /** How often each show publishes, from its last episodes' dates. */
    val rhythms: Flow<Map<Long, Rhythm>> = eq.selectPublished().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.groupBy { it.podcast_id }.mapNotNull { (id, r) -> rhythmOf(r.mapNotNull { it.published_at })?.let { id to it } }.toMap()
    }

    val favorites: Flow<List<EpisodeItem>> = eq.selectFavorites().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map { r ->
            episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url,
                r.chapters_url, r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url, r.favorite_at)
        }
    }

    suspend fun setFavorite(id: Long, on: Boolean) = withContext(Dispatchers.IO) { eq.setFavorite(if (on) clock.now() else null, id) }

    /**
     * Episodes of a show whose title or description has every word of [query]
     * (device feedback 2026-10-11: looking for a topic), newest first — all of
     * them, not a page. Case does not count, nor "ue" against "ü".
     */
    suspend fun search(podcastId: Long, query: String): List<EpisodeItem> = withContext(Dispatchers.Default) {
        val words = searchWords(query)
        if (words.isEmpty()) return@withContext emptyList()
        val rows = withContext(Dispatchers.IO) { eq.selectSearchable(podcastId).executeAsList() }
        val hits = rows.filter { r ->
            episodeMatches(r.title, runCatching { json.decodeFromString(Episode.serializer(), r.meta_json).description }.getOrNull(), words)
        }.map { it.id }.toSet()
        if (hits.isEmpty()) return@withContext emptyList()
        withContext(Dispatchers.IO) { eq.selectByPodcast(podcastId).executeAsList() }.filter { it.id in hits }.map { it.toItem() }
    }

    private val transcripts = mutableMapOf<Long, List<space.sovereigncast.hopp.feed.Cue>>()

    /** Whether the episode names a transcript at all — the player's chip. */
    suspend fun hasTranscript(id: Long): Boolean = details(id)?.transcripts?.isNotEmpty() == true

    /**
     * The episode's transcript (ADR 0012) in the best format the feed offers,
     * kept in memory for the session; empty when there is none.
     */
    suspend fun transcript(item: EpisodeItem): List<space.sovereigncast.hopp.feed.Cue> {
        transcripts[item.id]?.let { return it }
        val ref = space.sovereigncast.hopp.feed.TranscriptParser.pick(details(item.id)?.transcripts.orEmpty()) ?: return emptyList()
        val response = http.getLimited(ref.url, Limits.TRANSCRIPT)
        if (!response.status.isSuccess()) throw IllegalStateException("transcript: HTTP ${response.status.value}")
        val text = response.text()
        val cues = withContext(Dispatchers.Default) { space.sovereigncast.hopp.feed.TranscriptParser.parse(text, ref.type, ref.url) }
        if (transcripts.size > 8) transcripts.clear()
        transcripts[item.id] = cues
        return cues
    }

    private fun space.sovereigncast.hopp.db.Episode.toItem() = episodeItem(
        id, podcast_id, guid, title, published_at, duration_seconds, image_url, enclosure_url, chapters_url,
        position_ms, played, in_inbox, download_state, download_path, favoriteAt = favorite_at,
    )
}
