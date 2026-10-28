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
import space.sovereigncast.hopp.platform.ioDispatcher
import space.sovereigncast.hopp.sync.SyncDocs
import space.sovereigncast.hopp.sync.SyncJournal

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
    /** The feed's categories, as it names them (itunes:category). */
    val categories: List<String> = emptyList(),
    /** Seconds skipped at the start of each episode, and at its end; 0 for none. */
    val skipIntroSeconds: Int = 0,
    val skipOutroSeconds: Int = 0,
)

/** The most a show may skip at an episode's start or end: ten minutes. */
const val MAX_SKIP_SECONDS = 600

/** A show's numbers in the library: all its episodes, and the new ones among them. */
data class ShowCounts(val episodes: Int, val unheard: Int, val latestAt: Long? = null)

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
    /** The file's size as the feed states it, in bytes; null when it states none. */
    val enclosureLength: Long? = null,
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
    enclosureLength: Long? = null,
) = EpisodeItem(
    id, podcastId, guid, title ?: "", publishedAt, durationSeconds, imageUrl, enclosureUrl, chaptersUrl, positionMs,
    played == 1L, inInbox == 1L, podcastTitle, podcastImageUrl, downloadState.toInt(), downloadPath, favoriteAt != null,
    enclosureLength?.takeIf { it > 0 },
)

/** What the episode table keeps of a feed's `length`: 0 when there is none, so
 *  "states none" and "not read yet" (null, rows from before version 10) differ. */
internal fun space.sovereigncast.hopp.model.Enclosure?.lengthOrZero(): Long = this?.length ?: 0L

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
    /** Subscribing, unsubscribing and a show's own settings, noted for sync (ADR 0021, 5). */
    private val journal: SyncJournal = SyncJournal.Off,
    /** Whether automatic downloads are on at all (Settings.autoDownloads); a show's own switch comes on top. */
    private val autoDownloads: suspend () -> Boolean = { true },
) {
    private val q = db.podcastQueries
    private val eq = db.episodeQueries

    /** The show [id] as sync keeps it — after a change made here; not for one from sync. */
    private fun noteShow(id: Long, sync: Boolean) {
        if (!sync) return
        q.selectById(id).executeAsOneOrNull()?.let { r ->
            journal.record(SyncDocs.SUBSCRIPTIONS, SyncDocs.showKey(r.podcast_guid, r.feed_url),
                SyncDocs.show(r.feed_url, r.title, r.image_url, r.playback_speed?.toFloat(), r.auto_download == 1L, r.skip_intro_seconds.toInt(), r.skip_outro_seconds.toInt()))
        }
    }

    /** When any show was last refreshed, epoch seconds; null before the first. */
    val lastChecked: Flow<Long?> = q.lastChecked().asFlow().mapToOneOrNull(ioDispatcher).map { it?.last }

    val shows: Flow<List<Show>> = q.selectAll().asFlow().mapToList(ioDispatcher).map { rows ->
        rows.map { it.toShow() }
    }

    fun show(id: Long): Flow<Show?> = q.selectById(id).asFlow().mapToOneOrNull(ioDispatcher).map { it?.toShow() }

    private fun space.sovereigncast.hopp.db.Podcast.toShow() = Show(
        id, feed_url, title, author, image_url, last_error, auto_download == 1L, playback_speed?.toFloat(), podcast_guid,
        categories.lines().filter(String::isNotBlank), skip_intro_seconds.toInt(), skip_outro_seconds.toInt(),
    )

    suspend fun podcastMeta(id: Long): Podcast? = withContext(ioDispatcher) {
        q.selectById(id).executeAsOneOrNull()?.let { json.decodeFromString(Podcast.serializer(), it.meta_json) }
    }

    /** Fetch, parse, store. The newest episode lands in "New"; the back
     *  catalogue does not flood it. */
    /** [autoDownload]: the show's setting from the start — a restore brings it along, and nothing
     *  is fetched for a show that takes no downloads (security audit 2026-10-22, M-09). */
    suspend fun subscribe(input: String, sync: Boolean = true, autoDownload: Boolean = true): SubscribeResult {
        val url = FeedFetcher.normalize(input) ?: return SubscribeResult.Failed("not a feed address")
        withContext(ioDispatcher) { q.selectByFeedUrl(url).executeAsOneOrNull() }?.let {
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
        return subscribe(feedUrl, feed, fetched.etag, fetched.lastModified, sync, autoDownload)
    }

    /** Subscribes to a feed that is already fetched and parsed — the show page
     *  before subscribing has done both, and doing them twice was the slow
     *  part of subscribing (device feedback 2026-10-08). */
    suspend fun subscribe(feedUrl: String, feed: Feed, etag: String?, lastModified: String?, sync: Boolean = true, autoDownload: Boolean = true): SubscribeResult {
        withContext(ioDispatcher) { q.selectByFeedUrl(feedUrl).executeAsOneOrNull() }?.let {
            return SubscribeResult.AlreadySubscribed(it.id)
        }
        val now = clock.now()
        val fetch = autoDownload && autoDownloads()
        val id = withContext(ioDispatcher) {
            db.transactionWithResult<Long?> {
                // Subscribed meanwhile — another tap, or the same feed fetched in parallel: that one stands.
                if (q.selectByFeedUrl(feedUrl).executeAsOneOrNull() != null) return@transactionWithResult null
                val p = feed.podcast
                q.insert(feedUrl, p.podcastGuid, p.title ?: feedUrl, p.author, p.imageUrl,
                    json.encodeToString(Podcast.serializer(), p), etag, lastModified, now, now, p.categories.joinToString("\n"))
                val id = q.lastInsertId().executeAsOne()
                val newest = feed.episodes.maxByOrNull { it.publishedAt ?: 0 }?.guid
                if (!autoDownload) q.setAutoDownload(0, id)
                for (e in feed.episodes) insertEpisode(id, e, inInbox = e.guid == newest, now, fetch)
                id
            }?.also { noteShow(it, sync) }
        } ?: return SubscribeResult.AlreadySubscribed(withContext(ioDispatcher) { q.selectByFeedUrl(feedUrl).executeAsOne().id })
        if (fetch) downloads.schedule(manual = false)
        return SubscribeResult.Subscribed(id, feed.podcast.title ?: feedUrl)
    }

    /** Conditional fetch; new items go to "New", known ones keep the
     *  listener's state but take the feed's corrections. */
    suspend fun refresh(id: Long): Int {
        val row = withContext(ioDispatcher) { q.selectById(id).executeAsOneOrNull() } ?: return 0
        val now = clock.now()
        val result = try {
            fetcher.fetch(row.feed_url, row.etag, row.last_modified)
        } catch (e: Exception) {
            withContext(ioDispatcher) { q.markChecked(null, null, now, e.message ?: "network error", id) }
            throw e
        }
        if (result !is FetchResult.Fetched) {
            withContext(ioDispatcher) { q.markChecked(null, null, now, null, id) }
            return 0
        }
        val feed: Feed = withContext(Dispatchers.Default) { FeedParser.parse(result.bytes) }
        val fetch = row.auto_download == 1L && autoDownloads()
        val added = withContext(ioDispatcher) {
            db.transactionWithResult {
                result.movedTo?.let { moved -> if (q.selectByFeedUrl(moved).executeAsOneOrNull() == null) q.updateFeedUrl(moved, id) }
                val p = feed.podcast
                q.updateFromFeed(p.podcastGuid, p.title ?: row.title, p.author, p.imageUrl,
                    json.encodeToString(Podcast.serializer(), p), result.etag, result.lastModified, now, p.categories.joinToString("\n"), id)
                val known = eq.guidsOfPodcast(id).executeAsList().toSet()
                var added = 0
                for (e in feed.episodes) {
                    if (e.guid in known) {
                        eq.updateFromFeed(e.title, e.publishedAt, e.durationSeconds, e.imageUrl, e.enclosure?.url, e.enclosure.lengthOrZero(),
                            e.chaptersUrl, json.encodeToString(Episode.serializer(), e), id, e.guid)
                    } else {
                        insertEpisode(id, e, inInbox = true, now, autoDownload = fetch); added++
                    }
                }
                added
            }
        }
        if (added > 0 && fetch) downloads.schedule(manual = false)
        return added
    }

    /** Every subscription — or of them only [only] — looked at again, [parallel] at a time. */
    suspend fun refreshAll(parallel: Int = 4, only: Set<Long>? = null): RefreshSummary = coroutineScope {
        val ids = withContext(ioDispatcher) { q.selectAll().executeAsList().map { it.id } }.filter { only == null || it in only }
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

    /**
     * The show goes, with its episodes, progress, place in "Up next" and downloads. [onlyIf] is
     * asked in the same transaction, right before (sync: the removal from another device still
     * wins — delta audit 2 2026-10-22, HN-07); false leaves everything as it is. Whether it went.
     */
    suspend fun unsubscribe(id: Long, sync: Boolean = true, onlyIf: () -> Boolean = { true }): Boolean = withContext(ioDispatcher) {
        val paths = db.transactionWithResult<List<String>?> {
            if (!onlyIf()) return@transactionWithResult null
            if (sync) q.selectById(id).executeAsOneOrNull()?.let { r ->
                journal.record(SyncDocs.SUBSCRIPTIONS, SyncDocs.showKey(r.podcast_guid, r.feed_url), SyncDocs.show(r.feed_url, r.title, null, null, true), deleted = true)
            }
            val paths = eq.downloadPathsOfPodcast(id).executeAsList().filterNotNull()
            db.chapterCacheQueries.deleteByPodcast(id)
            db.commentQueries.deleteEventsByPodcast(id)
            db.commentQueries.deleteFetchesByPodcast(id)
            db.queueQueries.deleteByPodcast(id)
            eq.deleteByPodcast(id)
            q.delete(id)
            paths
        } ?: return@withContext false
        // The files only once the show is gone.
        paths.forEach { files?.delete(it) }
        true
    }

    suspend fun exportOpml(): String = withContext(ioDispatcher) {
        Opml.export(q.selectAll().executeAsList().map { Opml.Entry(it.feed_url, it.title) })
    }

    /** Subscribes to every feed of an OPML file that is not subscribed yet. */
    suspend fun importOpml(text: String, parallel: Int = 4): List<Pair<String, SubscribeResult>> = coroutineScope {
        val gate = Semaphore(parallel)
        // One subscription per address: the same feed twice in a file, subscribed at once, made the
        // second one fail on the database (CI 2026-10-22).
        Opml.parse(text).distinctBy { it.feedUrl }.map { entry ->
            async { gate.withPermit { entry.feedUrl to subscribe(entry.feedUrl) } }
        }.awaitAll()
    }

    /** [speed] null: the show follows the default speed again. */
    /** The change and its note for sync in one transaction: sync never sees one without the other (HN-07). */
    suspend fun setSpeed(id: Long, speed: Float?, sync: Boolean = true) = withContext(ioDispatcher) { db.transaction { q.setSpeed(speed?.toDouble(), id); noteShow(id, sync) } }
    suspend fun setAutoDownload(id: Long, on: Boolean, sync: Boolean = true) = withContext(ioDispatcher) { db.transaction { q.setAutoDownload(if (on) 1 else 0, id); noteShow(id, sync) } }
    /** Seconds skipped at the start and the end of each episode of show [id]; 0 for none, null: as it is —
     *  read in the same transaction, so a change to one never puts back an old value of the other. */
    suspend fun setSkip(id: Long, introSeconds: Int? = null, outroSeconds: Int? = null, sync: Boolean = true) = withContext(ioDispatcher) {
        db.transaction {
            val r = q.selectById(id).executeAsOneOrNull() ?: return@transaction
            val intro = introSeconds?.toLong() ?: r.skip_intro_seconds
            val outro = outroSeconds?.toLong() ?: r.skip_outro_seconds
            q.setSkip(intro.coerceIn(0, MAX_SKIP_SECONDS.toLong()), outro.coerceIn(0, MAX_SKIP_SECONDS.toLong()), id)
            noteShow(id, sync)
        }
    }

    /** New in "New" means: downloaded too, on Wi-Fi, if the show wants it (ADR 0012). */
    private fun insertEpisode(podcastId: Long, e: Episode, inInbox: Boolean, now: Long, autoDownload: Boolean) {
        val queued = inInbox && autoDownload && e.enclosure?.url != null
        eq.insertIfNew(podcastId, e.guid, e.title, e.publishedAt, e.durationSeconds, e.imageUrl, e.enclosure?.url, e.enclosure.lengthOrZero(),
            e.chaptersUrl, json.encodeToString(Episode.serializer(), e), if (inInbox) 1 else 0, now,
            if (queued) DownloadState.QUEUED.toLong() else DownloadState.NONE.toLong())
    }
}

class EpisodeRepository(
    private val db: HoppDatabase,
    private val http: HttpClient,
    private val clock: Clock,
    /** Progress and favourites, noted for sync (ADR 0021, 5). */
    private val journal: SyncJournal = SyncJournal.Off,
) {
    private val eq = db.episodeQueries

    /** Episode [id]'s progress as it stands now, for sync; [urgent]: see [SyncJournal.record]. */
    private fun noteProgress(id: Long, urgent: Boolean = true) {
        if (!journal.enabled) return
        val r = eq.syncKeysOf(id).executeAsOneOrNull() ?: return
        val show = SyncDocs.showKey(r.podcast_guid, r.feed_url)
        journal.record(SyncDocs.PROGRESS, SyncDocs.progressKey(show, r.guid), SyncDocs.progress(show, r.guid, r.position_ms, r.played == 1L, clock.now()), urgent = urgent)
    }

    private fun noteFavorite(id: Long) {
        if (!journal.enabled) return
        val r = eq.syncKeysOf(id).executeAsOneOrNull() ?: return
        val show = SyncDocs.showKey(r.podcast_guid, r.feed_url)
        journal.record(SyncDocs.PROGRESS, SyncDocs.favoriteKey(show, r.guid), SyncDocs.favorite(show, r.guid, r.favorite_at))
    }

    val inbox: Flow<List<EpisodeItem>> = eq.selectInbox().asFlow().mapToList(ioDispatcher).map { rows ->
        rows.map { r ->
            episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url,
                r.chapters_url, r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url, r.favorite_at, r.enclosure_length)
        }
    }

    /** Per show: how many episodes it has, and how many of them are new — the library's numbers. */
    val countsPerShow: Flow<Map<Long, ShowCounts>> = eq.countsPerPodcast().asFlow().mapToList(ioDispatcher).map { rows ->
        rows.associate { it.podcast_id to ShowCounts(it.total.toInt(), (it.unheard ?: 0L).toInt(), it.latest) }
    }

    val inProgress: Flow<List<EpisodeItem>> = eq.selectInProgress().asFlow().mapToList(ioDispatcher).map { rows ->
        rows.map { r ->
            episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url,
                r.chapters_url, r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url, r.favorite_at, r.enclosure_length)
        }
    }

    fun recentlyPlayed(limit: Long): Flow<List<EpisodeItem>> = eq.selectRecentlyPlayed(limit).asFlow().mapToList(ioDispatcher).map { rows ->
        rows.map { r ->
            episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url,
                r.chapters_url, r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url, r.favorite_at, r.enclosure_length)
        }
    }

    fun ofPodcast(podcastId: Long): Flow<List<EpisodeItem>> =
        eq.selectByPodcast(podcastId).asFlow().mapToList(ioDispatcher).map { rows -> rows.map { it.toItem() } }

    /** The newest [limit] episodes of a show (paging, device feedback 2026-10-08). */
    fun ofPodcastPage(podcastId: Long, limit: Long): Flow<List<EpisodeItem>> =
        eq.selectByPodcastPage(podcastId, limit).asFlow().mapToList(ioDispatcher).map { rows -> rows.map { it.toItem() } }

    fun countOfPodcast(podcastId: Long): Flow<Long> = eq.countByPodcast(podcastId).asFlow().mapToOne(ioDispatcher)

    /**
     * Episodes from before schema version 10 get their size from the feed data
     * already stored (user request 2026-10-18), [batch] at a time — once: then
     * every row has a length, 0 where the feed states none, and nothing is left.
     */
    suspend fun fillEnclosureLengths(batch: Long = 1000) = withContext(ioDispatcher) {
        val rows = eq.withoutLength(batch).executeAsList()
        if (rows.isEmpty()) return@withContext
        db.transaction {
            for (r in rows) {
                val length = runCatching { json.decodeFromString(Episode.serializer(), r.meta_json).enclosure.lengthOrZero() }.getOrDefault(0L)
                eq.setEnclosureLength(length, r.id)
            }
        }
    }

    /** One episode, live — its page follows played, position and download. */
    fun itemFlow(id: Long): Flow<EpisodeItem?> = eq.selectById(id).asFlow().mapToOneOrNull(ioDispatcher).map { it?.toItem() }

    suspend fun item(id: Long): EpisodeItem? = withContext(ioDispatcher) { eq.selectById(id).executeAsOneOrNull()?.toItem() }

    /** An episode of a show by its GUID — what a shared link names. */
    suspend fun byGuid(podcastId: Long, guid: String): EpisodeItem? =
        withContext(ioDispatcher) { eq.selectByGuid(podcastId, guid).executeAsOneOrNull()?.id }?.let { item(it) }

    suspend fun details(id: Long): Episode? = withContext(ioDispatcher) {
        eq.selectById(id).executeAsOneOrNull()?.let { json.decodeFromString(Episode.serializer(), it.meta_json) }
    }

    /** [paused]: the listener stopped here — sync sends it soon, not with the next round. */
    suspend fun savePosition(id: Long, positionMs: Long, paused: Boolean = false) = withContext(ioDispatcher) {
        eq.savePosition(positionMs, clock.now(), id)
        noteProgress(id, urgent = paused)
    }

    /** Heard, an episode leaves "Up next" too ([leaveQueue]). */
    suspend fun setPlayed(id: Long, played: Boolean) = withContext(ioDispatcher) {
        val left = db.transactionWithResult {
            if (played) eq.markPlayed(clock.now(), id) else eq.markUnplayed(id)
            if (played) leaveQueue(listOf(id)) else emptyList()
        }
        noteProgress(id)
        noteLeft(left)
    }

    /** Every episode of a show heard. */
    suspend fun setShowPlayed(podcastId: Long) = withContext(ioDispatcher) {
        val (ids, left) = db.transactionWithResult {
            val ids = eq.unplayedOfShow(podcastId).executeAsList()
            eq.markShowPlayed(clock.now(), podcastId)
            ids to leaveQueue(ids)
        }
        ids.forEach { noteProgress(it) }
        noteLeft(left)
    }

    /** This episode and every older one of its show heard — as Castamatic does. */
    suspend fun setPlayedUpTo(item: EpisodeItem) = withContext(ioDispatcher) {
        val (ids, left) = db.transactionWithResult {
            val ids = eq.unplayedUpTo(item.podcastId, item.id, item.publishedAt).executeAsList()
            eq.markPlayedUpTo(clock.now(), item.podcastId, item.id, item.publishedAt)
            ids to leaveQueue(ids)
        }
        ids.forEach { noteProgress(it) }
        noteLeft(left)
    }

    /** Progress from another device (sync): as it was there, and not noted again. Heard there, it
     *  leaves "Up next" here too — the other device notes that for the queue it shares, not for one
     *  a finished download put it in, which only this device knows. */
    suspend fun applyProgress(id: Long, positionMs: Long, played: Boolean, atSeconds: Long) = withContext(ioDispatcher) {
        db.transaction {
            if (played) { eq.markPlayed(atSeconds, id); leaveQueue(listOf(id)) } else { eq.markUnplayed(id); eq.savePosition(positionMs, atSeconds, id) }
        }
    }

    /**
     * Heard episodes out of "Up next" (device feedback 2026-10-25: one a finished download had put
     * there, marked heard and its download removed, still started after the episode before it —
     * streamed). Returns those that were in it.
     */
    private fun leaveQueue(ids: Collection<Long>): List<Long> {
        val queued = db.queueQueries.selectAll().executeAsList().toSet()
        return ids.filter { it in queued }.onEach { db.queueQueries.remove(it) }
    }

    /** Those out of "Up next", noted for sync as [QueueRepository] notes a removal. */
    private fun noteLeft(ids: List<Long>) {
        if (!journal.enabled) return
        for (id in ids) {
            val r = eq.syncKeysOf(id).executeAsOneOrNull() ?: continue
            val show = SyncDocs.showKey(r.podcast_guid, r.feed_url)
            journal.record(SyncDocs.QUEUE, SyncDocs.queueKey(show, r.guid), SyncDocs.queued(show, r.guid, 0), deleted = true)
        }
    }

    /** A favourite from another device (sync); [atSeconds] null: taken back. */
    suspend fun applyFavorite(id: Long, atSeconds: Long?) = withContext(ioDispatcher) { eq.setFavorite(atSeconds, id) }

    suspend fun dismiss(id: Long) = withContext(ioDispatcher) { eq.setInbox(0, id) }
    suspend fun clearInbox() = withContext(ioDispatcher) { eq.clearInbox() }

    /** Chapters from the cache, else fetched and cached (offline after the first play). */
    suspend fun chapters(item: EpisodeItem): List<Chapter> {
        val url = item.chaptersUrl ?: return emptyList()
        withContext(ioDispatcher) { db.chapterCacheQueries.select(item.id).executeAsOneOrNull() }?.let {
            return runCatching { ChaptersParser.parse(it) }.getOrDefault(emptyList())
        }
        val response = http.getLimited(url, Limits.CHAPTERS)
        // An error page is no chapter file, and nothing to keep.
        if (!response.status.isSuccess()) throw IllegalStateException("chapters: HTTP ${response.status.value}")
        val text = response.text()
        val chapters = withContext(Dispatchers.Default) { ChaptersParser.parse(text) }
        withContext(ioDispatcher) { db.chapterCacheQueries.upsert(item.id, text, clock.now()) }
        return chapters
    }

    /** How often each show publishes, from its last episodes' dates. */
    val rhythms: Flow<Map<Long, Rhythm>> = eq.selectPublished().asFlow().mapToList(ioDispatcher).map { rows ->
        rows.groupBy { it.podcast_id }.mapNotNull { (id, r) -> rhythmOf(r.mapNotNull { it.published_at })?.let { id to it } }.toMap()
    }

    val favorites: Flow<List<EpisodeItem>> = eq.selectFavorites().asFlow().mapToList(ioDispatcher).map { rows ->
        rows.map { r ->
            episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url,
                r.chapters_url, r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url, r.favorite_at, r.enclosure_length)
        }
    }

    suspend fun setFavorite(id: Long, on: Boolean) = withContext(ioDispatcher) { eq.setFavorite(if (on) clock.now() else null, id); noteFavorite(id) }

    /**
     * Episodes of a show whose title or description has every word of [query]
     * (device feedback 2026-10-11: looking for a topic), newest first — all of
     * them, not a page. Case does not count, nor "ue" against "ü".
     */
    suspend fun search(podcastId: Long, query: String): List<EpisodeItem> = withContext(Dispatchers.Default) {
        val words = searchWords(query)
        if (words.isEmpty()) return@withContext emptyList()
        val rows = withContext(ioDispatcher) { eq.selectSearchable(podcastId).executeAsList() }
        val hits = rows.filter { r ->
            episodeMatches(r.title, runCatching { json.decodeFromString(Episode.serializer(), r.meta_json).description }.getOrNull(), words)
        }.map { it.id }.toSet()
        if (hits.isEmpty()) return@withContext emptyList()
        withContext(ioDispatcher) { eq.selectByPodcast(podcastId).executeAsList() }.filter { it.id in hits }.map { it.toItem() }
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
        position_ms, played, in_inbox, download_state, download_path, favoriteAt = favorite_at, enclosureLength = enclosure_length,
    )
}
