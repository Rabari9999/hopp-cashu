package space.sovereigncast.hopp.sovereigncast

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.sovereigncast.hopp.data.Clock
import space.sovereigncast.hopp.directory.feedKey

/** One show in the curated list of Sovereigncast shows (ADR 0008). */
@Serializable data class ScDirectoryShow(
    @SerialName("podcast_guid") val podcastGuid: String,
    val title: String,
    @SerialName("feed_url") val feedUrl: String,
    val instance: String,
    @SerialName("image_url") val imageUrl: String? = null,
    val author: String? = null,
    val language: String? = null,
)

@Serializable private data class ScDirectoryFile(val version: Int = 1, val shows: List<ScDirectoryShow> = emptyList())

/**
 * The curated list of Sovereigncast shows on sovereigncast.space (ADR 0008):
 * what lets search mark and filter them. No directory knows `podcast:txt`, and
 * fetching every result's feed to look for the marker would cost a request per
 * row — so the list says which shows run on an instance, and each show's own
 * page still verifies it with the instance (ADR 0002). Instances join by merge
 * request; Nostr announcements replace the gatekeeping later.
 */
class ScDirectory(private val http: HttpClient, private val clock: Clock, private val url: String = URL) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var cached: List<ScDirectoryShow>? = null
    private var fetchedAt = 0L

    /** The list as last fetched, without waiting; null before the first answer. */
    val known: List<ScDirectoryShow>? get() = cached

    /** The list, kept for a day; empty when it cannot be had (offline, not there yet). */
    suspend fun shows(): List<ScDirectoryShow> = lock.withLock {
        val c = cached
        if (c != null && clock.now() - fetchedAt < KEEP_SECONDS) return c
        val fresh = runCatching {
            val r = http.get(url)
            if (!r.status.isSuccess()) null else json.decodeFromString(ScDirectoryFile.serializer(), r.bodyAsText()).shows
        }.getOrNull()
        if (fresh != null) { cached = fresh; fetchedAt = clock.now() }
        fresh ?: c ?: emptyList()
    }

    /** Whether a directory result is a listed Sovereigncast show — by GUID, else by feed. */
    fun matches(shows: List<ScDirectoryShow>, podcastGuid: String?, feedUrl: String): Boolean =
        shows.any { s -> (podcastGuid != null && s.podcastGuid.equals(podcastGuid, ignoreCase = true)) || feedKey(s.feedUrl) == feedKey(feedUrl) }

    companion object {
        const val URL = "https://sovereigncast.space/directory.json"
        private const val KEEP_SECONDS = 24 * 3600L
    }
}
