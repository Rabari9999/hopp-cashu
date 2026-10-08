package space.sovereigncast.hopp.directory

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import space.sovereigncast.hopp.crypto.Sha1
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.data.Clock
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.SettingsRepository

/** Where a search result came from — fyyd and Apple only when the listener
 *  switched them on, because each sees the searches (ADR 0008). */
enum class Source { PodcastIndex, Fyyd, Apple }

/** A show as a directory knows it. The show page before subscribing loads the
 *  real feed; this is only enough to list it. */
data class DirectoryShow(
    val title: String,
    val feedUrl: String,
    val author: String? = null,
    val imageUrl: String? = null,
    val podcastGuid: String? = null,
    /** Known to have a Lightning value block; null = the directory does not say. */
    val hasValue: Boolean? = null,
    val source: Source = Source.PodcastIndex,
)

data class PodcastIndexCredentials(val key: String, val secret: String)

class DirectoryException(message: String) : Exception(message)

private val json = Json { ignoreUnknownKeys = true }

private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/**
 * The Podcast Index API (ADR 0008): every request signed with
 * SHA-1(key + secret + unix time). It filters to shows with a Lightning value
 * block and resolves `podcast:remoteItem` GUIDs — what P2.0 players need.
 */
class PodcastIndexClient(private val http: HttpClient, private val clock: Clock) {
    private suspend fun get(path: String, credentials: PodcastIndexCredentials, vararg params: Pair<String, String>): JsonObject {
        val now = clock.now().toString()
        val response = http.get("$BASE/$path") {
            params.forEach { (k, v) -> parameter(k, v) }
            header("X-Auth-Key", credentials.key)
            header("X-Auth-Date", now)
            header(HttpHeaders.Authorization, Sha1.digest((credentials.key + credentials.secret + now).encodeToByteArray()).toHex())
        }
        if (!response.status.isSuccess()) throw DirectoryException("Podcast Index: HTTP ${response.status.value}")
        return json.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: throw DirectoryException("Podcast Index: no JSON object")
    }

    suspend fun search(term: String, valueOnly: Boolean, credentials: PodcastIndexCredentials): List<DirectoryShow> {
        val params = buildList {
            add("q" to term); add("max" to "40")
            if (valueOnly) add("val" to "lightning")
        }
        return get("search/byterm", credentials, *params.toTypedArray())["feeds"].objects()
            .mapNotNull { show(it, valueKnown = valueOnly) }
    }

    /** The show with this `podcast:guid`, or null. The index answers with any
     *  feed claiming the GUID, so it is checked to really be that one. */
    suspend fun byGuid(guid: String, credentials: PodcastIndexCredentials): DirectoryShow? {
        val feed = get("podcasts/byguid", credentials, "guid" to guid)["feed"] as? JsonObject ?: return null
        return show(feed, valueKnown = false)?.takeIf { it.podcastGuid.equals(guid, ignoreCase = true) }
    }

    private fun show(o: JsonObject, valueKnown: Boolean): DirectoryShow? {
        if ((o["dead"] as? JsonPrimitive)?.intOrNull == 1) return null
        val url = o.str("url") ?: return null
        val value = o["value"] as? JsonObject
        return DirectoryShow(
            title = o.str("title") ?: return null,
            feedUrl = url,
            author = o.str("author") ?: o.str("ownerName"),
            imageUrl = o.str("artwork") ?: o.str("image"),
            podcastGuid = o.str("podcastGuid"),
            hasValue = when {
                valueKnown -> true
                value != null -> (value["model"] as? JsonObject)?.str("type") == "lightning"
                else -> null
            },
        )
    }

    companion object { const val BASE = "https://api.podcastindex.org/api/1.0" }
}

/** fyyd — strong for German-language shows; no key. */
class FyydClient(private val http: HttpClient) {
    suspend fun search(term: String): List<DirectoryShow> {
        val response = http.get("https://api.fyyd.de/0.2/search/podcast") { parameter("term", term); parameter("count", "20") }
        if (!response.status.isSuccess()) throw DirectoryException("fyyd: HTTP ${response.status.value}")
        val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: return emptyList()
        return root["data"].objects().mapNotNull { o ->
            DirectoryShow(
                title = o.str("title") ?: return@mapNotNull null,
                feedUrl = o.str("xmlURL") ?: return@mapNotNull null,
                author = o.str("author"),
                imageUrl = o.str("layoutImageURL") ?: o.str("imgURL"),
                source = Source.Fyyd,
            )
        }
    }
}

/** Apple's iTunes Search API — mainstream catalogue, feed URL only. */
class AppleClient(private val http: HttpClient) {
    suspend fun search(term: String): List<DirectoryShow> {
        val response = http.get("https://itunes.apple.com/search") {
            parameter("media", "podcast"); parameter("entity", "podcast"); parameter("term", term); parameter("limit", "25")
        }
        if (!response.status.isSuccess()) throw DirectoryException("Apple: HTTP ${response.status.value}")
        val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: return emptyList()
        return root["results"].objects().mapNotNull { o ->
            DirectoryShow(
                title = o.str("collectionName") ?: return@mapNotNull null,
                feedUrl = o.str("feedUrl") ?: return@mapNotNull null, // shows without RSS (exclusives) are unreachable
                author = o.str("artistName"),
                imageUrl = o.str("artworkUrl600") ?: o.str("artworkUrl100"),
                source = Source.Apple,
            )
        }
    }
}

data class SearchResult(
    val shows: List<DirectoryShow>,
    /** Sources that were asked and did not answer. */
    val failed: List<Source> = emptyList(),
    /** No source could be asked at all (no key, nothing else switched on). */
    val unavailable: Boolean = false,
)

/** Two addresses of one feed: scheme, `www.`, case of the host and a trailing slash do not count. */
internal fun feedKey(url: String): String {
    val noScheme = url.trim().substringAfter("://")
    val host = noScheme.substringBefore('/').lowercase().removePrefix("www.")
    val path = noScheme.substringAfter('/', "").trimEnd('/')
    return "$host/$path"
}

/**
 * Discover's sources (ADR 0008): the Podcast Index always, fyyd and Apple
 * when switched on, merged with the Podcast Index first and each show listed
 * once. Podroll recommendations come from the subscribed shows' own feeds.
 */
class DirectoryRepository(
    private val podcastIndex: PodcastIndexClient,
    private val fyyd: FyydClient,
    private val apple: AppleClient,
    private val settings: SettingsRepository,
    private val podcasts: PodcastRepository,
    private val builtIn: PodcastIndexCredentials? = BuiltInKeys.PODCAST_INDEX_KEY.takeIf { it.isNotEmpty() }
        ?.let { PodcastIndexCredentials(it, BuiltInKeys.PODCAST_INDEX_SECRET) },
) {
    private var podrollCache: Pair<Set<String>, List<DirectoryShow>>? = null

    private suspend fun credentials(): PodcastIndexCredentials? {
        val s = settings.current()
        return if (s.podcastIndexKey.isNotBlank() && s.podcastIndexSecret.isNotBlank()) PodcastIndexCredentials(s.podcastIndexKey.trim(), s.podcastIndexSecret.trim())
        else builtIn
    }

    /** With [valueOnly], only the Podcast Index can answer — the others do not know. */
    suspend fun search(term: String, valueOnly: Boolean): SearchResult = coroutineScope {
        val s = settings.current()
        val credentials = credentials()
        val asks = buildList {
            if (credentials != null) add(Source.PodcastIndex to async { runCatching { podcastIndex.search(term, valueOnly, credentials) } })
            if (!valueOnly && s.fyydSearch) add(Source.Fyyd to async { runCatching { fyyd.search(term) } })
            if (!valueOnly && s.appleSearch) add(Source.Apple to async { runCatching { apple.search(term) } })
        }
        if (asks.isEmpty()) return@coroutineScope SearchResult(emptyList(), unavailable = true)
        val answers = asks.map { (source, job) -> source to job.await() }
        val seen = mutableSetOf<String>()
        val shows = answers.flatMap { it.second.getOrNull().orEmpty() }.filter { show ->
            val keys = listOfNotNull(feedKey(show.feedUrl), show.podcastGuid?.lowercase())
            val fresh = keys.none { it in seen }
            seen += keys
            fresh
        }
        SearchResult(shows, failed = answers.filter { it.second.isFailure }.map { it.first })
    }

    /** "From your shows": what the subscribed shows recommend in their
     *  `podcast:podroll`, resolved via the Podcast Index, without what is
     *  already subscribed. Kept until the subscriptions change. */
    suspend fun podroll(limit: Int = 20): List<DirectoryShow> {
        val credentials = credentials() ?: return emptyList()
        val shows = podcasts.shows.first()
        val subscribed = shows.flatMap { listOfNotNull(feedKey(it.feedUrl), it.podcastGuid?.lowercase()) }.toSet()
        podrollCache?.let { (key, list) -> if (key == subscribed) return list }
        val wanted = shows.flatMap { podcasts.podcastMeta(it.id)?.podroll.orEmpty() }
            .distinctBy { it.feedGuid.lowercase() }
            .filter { it.feedGuid.lowercase() !in subscribed && (it.feedUrl == null || feedKey(it.feedUrl) !in subscribed) }
            .take(limit)
        val gate = Semaphore(4)
        val resolved = coroutineScope {
            wanted.map { item -> async { gate.withPermit { runCatching { podcastIndex.byGuid(item.feedGuid, credentials) }.getOrNull() } } }
                .mapNotNull { it.await() }
        }.filter { feedKey(it.feedUrl) !in subscribed }
        podrollCache = subscribed to resolved
        return resolved
    }
}
