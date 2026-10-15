package space.sovereigncast.hopp.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import space.sovereigncast.hopp.db.HoppDatabase

object DownloadState {
    const val NONE = 0
    const val QUEUED = 1
    const val DOWNLOADING = 2
    const val DONE = 3
    const val FAILED = 4
}

/** Starts the platform's background work (WorkManager on Android): requested
 *  downloads on any network, automatic ones on Wi-Fi only (ADR 0012). */
fun interface DownloadScheduler {
    fun schedule(manual: Boolean)
    companion object { val None = DownloadScheduler { } }
}

/** Where downloads live — outside the OS backup (ADR 0015 backs up everything
 *  but keys; a gigabyte of audio is not worth backing up). */
class DownloadFiles(val dir: String) {
    fun path(episodeId: Long, url: String) = Path(dir, "$episodeId.${extension(url)}")
    fun partial(episodeId: Long) = Path(dir, "$episodeId.part")
    fun size(path: Path): Long = SystemFileSystem.metadataOrNull(path)?.size ?: 0L
    fun exists(path: String) = SystemFileSystem.exists(Path(path))
    fun delete(path: String) = runCatching { SystemFileSystem.delete(Path(path), mustExist = false) }.let { }
    fun ensureDir() = SystemFileSystem.createDirectories(Path(dir))

    private fun extension(url: String): String {
        val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return ext.takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "audio"
    }
}

/** Thrown for answers that will not get better by retrying (404, 410 …, too large, not audio). */
class DownloadRefused(val status: Int, why: String = "HTTP $status") : Exception(why)

/**
 * Downloads (ADR 0012): automatic for new episodes on Wi-Fi, by request on any
 * network, bounded by a storage limit — the oldest unplayed go first — and
 * played ones removed after 24 hours. An interrupted download resumes from its
 * `.part` file with a Range request.
 */
class DownloadRepository(
    private val db: HoppDatabase,
    http: HttpClient,
    private val clock: Clock,
    private val files: DownloadFiles,
    private val scheduler: DownloadScheduler,
    private val settings: SettingsRepository,
) {
    private val eq = db.episodeQueries
    // Podcast hosts redirect through trackers, and some of them to plain http.
    private val client = http.config { install(HttpRedirect) { allowHttpsDowngrade = true } }
    private val running = Mutex()
    private val cancelled = MutableStateFlow<Set<Long>>(emptySet())

    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    /** Episode id → 0..1 while it downloads (missing when the size is unknown). */
    val progress: StateFlow<Map<Long, Float>> = _progress.asStateFlow()

    val downloads: Flow<List<EpisodeItem>> = eq.selectDownloads().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map { r ->
            episodeItem(r.id, r.podcast_id, r.guid, r.title, r.published_at, r.duration_seconds, r.image_url, r.enclosure_url,
                r.chapters_url, r.position_ms, r.played, r.in_inbox, r.download_state, r.download_path, r.podcast_title, r.podcast_image_url, r.favorite_at)
        }
    }

    val usedBytes: Flow<Long> = eq.downloadedBytes().asFlow().mapToOne(Dispatchers.IO)
    val downloadedCount: Flow<Long> = eq.downloadedCount().asFlow().mapToOne(Dispatchers.IO)

    /** Every download — waiting, running, done or failed — and every file left
     *  in the folder (a .part of an interrupted one); running ones stop. */
    suspend fun removeAll() = withContext(Dispatchers.IO) {
        eq.downloadIds().executeAsList().forEach { remove(it) }
        runCatching { SystemFileSystem.list(Path(files.dir)).forEach { SystemFileSystem.delete(it, mustExist = false) } }
        Unit
    }

    /** The listener asked for it: any network. */
    suspend fun request(episodeId: Long) {
        withContext(Dispatchers.IO) { eq.requestDownload(1, episodeId) }
        scheduler.schedule(manual = true)
    }

    /**
     * A download waiting for Wi-Fi — an automatic one — goes now, over mobile
     * data too (device feedback 2026-10-15: before, it had to be cancelled and
     * requested again). [episodeId] null: every one that waits.
     */
    suspend fun startNow(episodeId: Long? = null) {
        withContext(Dispatchers.IO) { if (episodeId != null) eq.downloadNow(episodeId) else eq.downloadAllNow() }
        scheduler.schedule(manual = true)
    }

    /** Automatic downloads still waiting for Wi-Fi. */
    val waitingCount: Flow<Long> = eq.waitingCount().asFlow().mapToOne(Dispatchers.IO)

    /** Stops a download or removes a finished one. */
    suspend fun remove(episodeId: Long) = withContext(Dispatchers.IO) {
        cancelled.update { it + episodeId }
        eq.selectById(episodeId).executeAsOneOrNull()?.download_path?.let(files::delete)
        files.delete(files.partial(episodeId).toString())
        eq.clearDownload(episodeId)
    }

    /**
     * Works through the queue, one at a time. [includeAuto] is false when the
     * platform says the network is metered. Returns false when something is
     * worth trying again later (the network went away).
     */
    suspend fun runPending(includeAuto: Boolean): Boolean = running.withLock {
        withContext(Dispatchers.IO) { files.ensureDir(); eq.resetInterrupted() }
        var ok = true
        while (true) {
            val next = withContext(Dispatchers.IO) { eq.nextDownload(if (includeAuto) 1L else 0L).executeAsOneOrNull() } ?: break
            cancelled.update { it - next.id }
            try {
                download(next.id, next.enclosure_url!!)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadRefused) {
                // Refused for good: nothing to resume, so no .part left behind either.
                withContext(Dispatchers.IO) { files.delete(files.partial(next.id).toString()); eq.setDownloadState(DownloadState.FAILED.toLong(), next.id) }
            } catch (e: DownloadCancelled) {
                // remove() has already cleared it.
            } catch (e: Exception) {
                // Network gone: back in the queue, the .part file stays for the retry.
                withContext(Dispatchers.IO) { eq.setDownloadState(DownloadState.QUEUED.toLong(), next.id) }
                ok = false
                break
            } finally {
                _progress.update { it - next.id }
            }
            cleanup()
        }
        ok
    }

    private class DownloadCancelled : Exception()

    private suspend fun download(id: Long, url: String) {
        withContext(Dispatchers.IO) { eq.setDownloadState(DownloadState.DOWNLOADING.toLong(), id) }
        val part = files.partial(id)
        val have = files.size(part)
        client.prepareGet(url) {
            if (have > 0) header(HttpHeaders.Range, "bytes=$have-")
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS; socketTimeoutMillis = 60_000 }
        }.execute { response ->
            val status = response.status
            if (status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                // The .part was already whole, or the file changed: start over.
                files.delete(part.toString()); throw IllegalStateException("range not satisfiable")
            }
            if (!status.isSuccess()) {
                if (status.value in 400..499 && status.value != 408 && status.value != 429) throw DownloadRefused(status.value)
                throw IllegalStateException("HTTP ${status.value}")
            }
            // A web page is no episode — a tracker's error page, a login wall
            // (security review 2026-10-10, M-03).
            val type = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
            if (type in NOT_MEDIA) { files.delete(part.toString()); throw DownloadRefused(status.value, "not an episode: $type") }
            val append = status == HttpStatusCode.PartialContent
            val offset = if (append) have else 0L
            val total = response.contentLength()?.let { it + offset }
            // At most one episode's worth, and never more than the storage limit
            // allows: checked against the announced size and again while writing,
            // so an endless stream cannot fill the phone before cleanup() runs.
            val cap = maxBytes()
            if (total != null && total > cap) { files.delete(part.toString()); throw DownloadRefused(status.value, "too large: $total bytes") }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var written = offset
            var reported = -1
            withContext(Dispatchers.IO) {
                SystemFileSystem.sink(part, append = append).buffered().use { sink ->
                    while (true) {
                        val n = channel.readAvailable(buffer, 0, buffer.size)
                        if (n < 0) break
                        if (id in cancelled.value) throw DownloadCancelled()
                        if (written + n > cap) throw DownloadRefused(status.value, "too large: over $cap bytes")
                        sink.write(buffer, 0, n)
                        written += n
                        if (total != null && total > 0) {
                            val percent = (written * 100 / total).toInt()
                            if (percent != reported) { reported = percent; _progress.update { it + (id to written.toFloat() / total) } }
                        }
                    }
                }
            }
            if (total != null && written < total) throw IllegalStateException("incomplete: $written of $total")
        }
        withContext(Dispatchers.IO) {
            val target = files.path(id, url)
            SystemFileSystem.atomicMove(part, target)
            eq.setDownloaded(target.toString(), files.size(target), id)
        }
    }

    /** The most one download may take: [MAX_EPISODE_BYTES], or the storage limit if that is smaller. */
    private suspend fun maxBytes(): Long {
        val limit = settings.current().storageLimitMb.toLong() * 1024 * 1024
        return if (limit > 0) minOf(limit, MAX_EPISODE_BYTES) else MAX_EPISODE_BYTES
    }

    /** Played a day ago → gone; over the limit → the oldest unplayed go; a
     *  file that disappeared → no longer listed as downloaded. */
    suspend fun cleanup() = withContext(Dispatchers.IO) {
        val dayAgo = clock.now() - 24 * 3600
        eq.playedDownloadsBefore(dayAgo).executeAsList().forEach { r ->
            r.download_path?.let(files::delete); eq.clearDownload(r.id)
        }
        val unplayed = eq.unplayedDownloadsOldestFirst().executeAsList()
        unplayed.filter { r -> r.download_path?.let(files::exists) != true }.forEach { eq.clearDownload(it.id) }
        val limit = settings.current().storageLimitMb.toLong() * 1024 * 1024
        if (limit > 0) {
            var used = eq.downloadedBytes().executeAsOne()
            // Never the newest one — that is what the listener is about to hear.
            for (r in unplayed.dropLast(1)) {
                if (used <= limit) break
                r.download_path?.let(files::delete); eq.clearDownload(r.id)
                used -= r.download_size ?: 0
            }
        }
    }

    private companion object {
        /** Larger than any audio episode, room for video ones. */
        const val MAX_EPISODE_BYTES = 4L shl 30
        val NOT_MEDIA = setOf("text/html", "application/xhtml+xml", "application/json", "text/xml")
    }
}
