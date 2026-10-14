package space.sovereigncast.hopp.directory

import space.sovereigncast.hopp.net.getLimited
import space.sovereigncast.hopp.net.Limits
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
        val response = http.getLimited("$BASE/$path", Limits.DIRECTORY) {
            params.forEach { (k, v) -> parameter(k, v) }
            header("X-Auth-Key", credentials.key)
            header("X-Auth-Date", now)
            header(HttpHeaders.Authorization, Sha1.digest((credentials.key + credentials.secret + now).encodeToByteArray()).toHex())
        }
        if (!response.status.isSuccess()) throw DirectoryException("Podcast Index: HTTP ${response.status.value}")
        return json.parseToJsonElement(response.text()) as? JsonObject ?: throw DirectoryException("Podcast Index: no JSON object")
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
        val response = http.getLimited("https://api.fyyd.de/0.2/search/podcast", Limits.DIRECTORY) { parameter("term", term); parameter("count", "20") }
        if (!response.status.isSuccess()) throw DirectoryException("fyyd: HTTP ${response.status.value}")
        val root = json.parseToJsonElement(response.text()) as? JsonObject ?: return emptyList()
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
        val response = http.getLimited("https://itunes.apple.com/search", Limits.DIRECTORY) {
            parameter("media", "podcast"); parameter("entity", "podcast"); parameter("term", term); parameter("limit", "25")
        }
        if (!response.status.isSuccess()) throw DirectoryException("Apple: HTTP ${response.status.value}")
        val root = json.parseToJsonElement(response.text()) as? JsonObject ?: return emptyList()
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
    /** No source could be asked at all (a build without key, nothing else switched on). */
    val unavailable: Boolean = false,
)

/**
 * The other spellings of a German search term: "muenzweg" also as
 * "münzweg", "münzweg" also as "muenzweg" — the directories match letters,
 * not sounds (device feedback 2026-10-08: "Muenzweg" found nothing). Only
 * spellings that differ from the term; at most two.
 */
internal fun spellings(term: String): List<String> {
    val toUmlaut = term
        .replace("ae", "ä").replace("oe", "ö").replace("ue", "ü")
        .replace("Ae", "Ä").replace("Oe", "Ö").replace("Ue", "Ü")
        .replace("AE", "Ä").replace("OE", "Ö").replace("UE", "Ü")
    val toAscii = term
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
        .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss")
    return listOf(toUmlaut, toAscii).filter { it != term }.distinct()
}

/**
 * Whether a show's title starts to match what is typed (device feedback
 * 2026-10-14: "Nodesig" found nothing). The directories match whole words
 * only — the Podcast Index even with `similar`, and fyyd — so the
 * suggestions while typing are matched here: case, umlauts ("muenzw" finds
 * "Münzweg"), spaces and punctuation do not count, the typed letters only
 * have to appear in the title in one run.
 */
internal fun titleMatches(title: String, typed: String): Boolean {
    fun norm(s: String) = s.lowercase()
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        .filter { it.isLetterOrDigit() }
    val t = norm(typed)
    return t.length >= 2 && t in norm(title)
}

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

    // The app's own key only: a field for the listener's would confuse far
    // more people than it helps (ADR 0008, amended 2026-10-08).
    private fun credentials(): PodcastIndexCredentials? = builtIn

    /** With [valueOnly], only the Podcast Index can answer — the others do not know. */
    suspend fun search(term: String, valueOnly: Boolean): SearchResult = coroutineScope {
        val s = settings.current()
        val credentials = credentials()
        val terms = listOf(term) + spellings(term)
        // Each source with every spelling; the term's own results first, the
        // other spellings only add what it did not find.
        fun ask(search: suspend (String) -> List<DirectoryShow>) = async {
            val answers = terms.map { t -> async { runCatching { search(t) } } }.map { it.await() }
            if (answers.first().isFailure && answers.all { it.isFailure }) answers.first() else Result.success(answers.flatMap { it.getOrNull().orEmpty() })
        }
        val asks = buildList {
            if (credentials != null) add(Source.PodcastIndex to ask { t -> podcastIndex.search(t, valueOnly, credentials) })
            if (!valueOnly && s.fyydSearch) add(Source.Fyyd to ask { t -> fyyd.search(t) })
            if (!valueOnly && s.appleSearch) add(Source.Apple to ask { t -> apple.search(t) })
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

    /** The feed address of the show with this `podcast:guid`, if the index knows it. */
    suspend fun feedUrlForGuid(guid: String): String? {
        val credentials = credentials() ?: return null
        return runCatching { podcastIndex.byGuid(guid, credentials) }.getOrNull()?.feedUrl
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
