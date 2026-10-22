package space.sovereigncast.hopp.nostr

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import space.sovereigncast.hopp.crypto.secureRandomBytes
import space.sovereigncast.hopp.crypto.toHex
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Sends an event to relays and says which of them took it (NIP-01 `OK`); reads
 *  an author's newest event of a kind back, or whatever matches a filter. */
class RelayPublisher(val connector: RelayConnector, private val timeout: Duration = 10.seconds) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The relays that answered `OK true`; one that fails or stays silent is left out. */
    suspend fun publish(event: Event, relays: List<String>): Set<String> = coroutineScope {
        relays.distinct().map { url -> async { url.takeIf { publishTo(event, url) } } }.awaitAll().filterNotNull().toSet()
    }

    /** The newest valid event of [kind] by [author] on any of [relays], or null. [settle]: see [query]. */
    suspend fun latest(kind: Int, author: String, relays: List<String>, settle: Duration? = null): Event? =
        latestChecked(kind, author, relays, settle).event

    /**
     * What a read of an author's newest event came to: [event] — null for none
     * found — and whether [complete]: every relay answered to its end. Null and
     * incomplete is "not known", not "none": a replaceable list (NIP-51 mutes,
     * NIP-65 relays) must not be written anew over that (security audit
     * 2026-10-20, M-04/M-08 — the relay holding the list was the one not
     * answering, and the new list went out without what it held).
     */
    data class Latest(val event: Event?, val complete: Boolean)

    suspend fun latestChecked(kind: Int, author: String, relays: List<String>, settle: Duration? = null): Latest {
        // A relay that closed the request, or was cut off, did not answer to the end: not complete.
        val got = gather(relays, settle) { url -> latestOn(kind, author, url) }
        return Latest(got.results.flatMap { it.events }.maxByOrNull { it.created_at }, got.complete && got.results.all { it.ended })
    }

    /**
     * Every valid event matching any of [filters] (NIP-01 filters, one REQ) on
     * [relays], each once. A relay that is slow to say EOSE keeps what it sent
     * until then; one that fails adds nothing. With [settle], the others get
     * that long once the first relay has answered — one that hangs costs the
     * timeout otherwise (device feedback 2026-10-16: 30–40 s for comments).
     */
    suspend fun query(filters: List<JsonObject>, relays: List<String>, settle: Duration? = null): List<Event> =
        // Checked before made unique: a forged copy from a fast relay must not hide the real event.
        gather(relays, settle) { url -> queryOn(filters, url) }.results.flatten().filter { it.isValid() }.distinctBy { it.id }

    /**
     * What [query] finds, and how many of [relays] answered it to its end
     * (`EOSE`) — a relay that dropped the connection halfway counts as not
     * answering. For a decision that must not rest on "nothing found" when
     * the relay holding it was the one that failed (the sync key, ADR 0021 5).
     */
    data class Checked(val events: List<Event>, val answered: Int, val asked: Int)

    suspend fun queryChecked(filters: List<JsonObject>, relays: List<String>, settle: Duration? = null): Checked {
        val got = gather(relays, settle) { url -> queryOn(filters, url, toTheEnd = true) }
        return Checked(got.results.flatten().filter { it.isValid() }.distinctBy { it.id }, got.results.size, relays.distinct().size)
    }

    /** What [gather] came to; [complete] when every relay answered — none unreachable, none cut off by the settle time. */
    private class Gathered<T>(val results: List<T>, val complete: Boolean)

    /** [one] on every relay at once; what each answered — null from one that could not be reached.
     *  An answer of nothing is an answer too: it starts the settle time. */
    private suspend fun <T : Any> gather(relays: List<String>, settle: Duration?, one: suspend (String) -> T?): Gathered<T> = coroutineScope {
        val urls = relays.distinct()
        // A box per relay: null inside is a relay without an answer, null outside the settle time running out.
        val results = Channel<Answer<T>>(Channel.UNLIMITED)
        val jobs = urls.map { url -> launch { results.send(Answer(runCatching { one(url) }.getOrNull())) } }
        val out = mutableListOf<T>()
        var until: TimeMark? = null
        var received = 0
        var answered = 0
        while (received < urls.size) {
            val left = until?.let { -it.elapsedNow() }
            val box = if (left == null) results.receive() else withTimeoutOrNull(left) { results.receive() } ?: break
            received++
            val r = box.value ?: continue
            answered++
            out += r
            if (settle != null && until == null) until = TimeSource.Monotonic.markNow() + settle
        }
        jobs.forEach { it.cancel() }
        Gathered(out, answered == urls.size)
    }

    private class Answer<T>(val value: T?)

    /**
     * What [url] holds for [filters]; null when it could not be reached — with [toTheEnd],
     * also when it did not answer up to `EOSE`. Only answers to this request count, only
     * events the filters ask for, and only so much (security audit 2026-10-22, M-07): a
     * relay sending more than [MAX_EVENTS] events or [MAX_BYTES] is cut off, and its
     * answer counts as not to the end.
     */
    private suspend fun queryOn(filters: List<JsonObject>, url: String, toTheEnd: Boolean = false): List<Event>? {
        val found = mutableListOf<Event>()
        var reached = false
        var ended = false
        withTimeoutOrNull(timeout) {
            val session = runCatching { connector.connect(url) }.getOrNull() ?: return@withTimeoutOrNull
            reached = true
            try {
                coroutineScope {
                    val sub = "hopp-" + secureRandomBytes(6).toHex()
                    var bytes = 0L
                    val done = async(start = CoroutineStart.UNDISPATCHED) {
                        session.incoming.mapNotNull { text ->
                            bytes += text.length
                            if (bytes > MAX_BYTES || found.size >= MAX_EVENTS) return@mapNotNull false
                            if (text.length > MAX_MESSAGE) return@mapNotNull null
                            runCatching {
                                val arr = json.parseToJsonElement(text).jsonArray
                                if (arr.getOrNull(1)?.jsonPrimitive?.content != sub) return@runCatching null
                                when (arr[0].jsonPrimitive.content) {
                                    "EVENT" -> {
                                        json.decodeFromJsonElement(Event.serializer(), arr[2]).takeIf { NostrFilter.matchesAny(it, filters) }?.let(found::add)
                                        null
                                    }
                                    "EOSE" -> true
                                    "CLOSED" -> false
                                    else -> null
                                }
                            }.getOrNull()
                        }.first()
                    }
                    session.send(buildJsonArray { add(JsonPrimitive("REQ")); add(JsonPrimitive(sub)); filters.forEach { add(it) } }.toString())
                    ended = done.await()
                    runCatching { session.send("""["CLOSE","$sub"]""") }
                }
            } catch (e: Exception) {
                // What arrived before the failure still counts.
            } finally {
                runCatching { session.close() }
            }
        }
        return found.toList().takeIf { reached && (ended || !toTheEnd) }
    }

    companion object {
        /** At most this many events from one relay for one request. */
        const val MAX_EVENTS = 5_000
        /** At most this much text from one relay for one request. */
        const val MAX_BYTES = 16L * 1024 * 1024
        /** A single message larger than this is passed over (an event holds 64 KiB of content). */
        const val MAX_MESSAGE = 512 * 1024
    }

    /** Whether [url] takes a connection and answers a request at all. */
    suspend fun reachable(url: String): Boolean =
        runCatching { latestOn(10002, "0".repeat(64), url) }.getOrNull() != null

    /** One relay's answer for [latestOn]: [ended] — it said `EOSE`; `CLOSED` is an answer, but not to the end. */
    private class LatestAnswer(val events: List<Event>, val ended: Boolean)

    /**
     * The newest match on [url]; null when it could not be reached or never answered. Held to
     * what [queryOn] is (delta audit 2026-10-22, MN-05): only answers to this request count,
     * only events it asked for, and only so much.
     */
    private suspend fun latestOn(kind: Int, author: String, url: String): LatestAnswer? = withTimeoutOrNull(timeout) {
        val session = runCatching { connector.connect(url) }.getOrNull() ?: return@withTimeoutOrNull null
        try {
            coroutineScope {
                val sub = "hopp-" + secureRandomBytes(6).toHex()
                val found = mutableListOf<Event>()
                var bytes = 0L
                val done = async(start = CoroutineStart.UNDISPATCHED) {
                    session.incoming.mapNotNull { text ->
                        bytes += text.length
                        if (bytes > MAX_BYTES || found.size >= MAX_EVENTS) return@mapNotNull false
                        if (text.length > MAX_MESSAGE) return@mapNotNull null
                        runCatching {
                            val arr = json.parseToJsonElement(text).jsonArray
                            if (arr.getOrNull(1)?.jsonPrimitive?.content != sub) return@runCatching null
                            when (arr[0].jsonPrimitive.content) {
                                "EVENT" -> { json.decodeFromJsonElement(Event.serializer(), arr[2]).takeIf { it.kind == kind && it.pubkey == author && it.isValid() }?.let(found::add); null }
                                "EOSE" -> true
                                "CLOSED" -> false
                                else -> null
                            }
                        }.getOrNull()
                    }.first()
                }
                session.send("""["REQ","$sub",{"kinds":[$kind],"authors":["$author"],"limit":1}]""")
                val ended = done.await()
                runCatching { session.send("""["CLOSE","$sub"]""") }
                LatestAnswer(listOfNotNull(found.maxByOrNull { it.created_at }), ended)
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { session.close() }
        }
    }

    private suspend fun publishTo(event: Event, url: String): Boolean = withTimeoutOrNull(timeout) {
        val session = runCatching { connector.connect(url) }.getOrNull() ?: return@withTimeoutOrNull false
        try {
            coroutineScope {
                // Listen before sending, so a fast OK is not missed.
                val ok = async(start = CoroutineStart.UNDISPATCHED) {
                    session.incoming.mapNotNull { text ->
                        runCatching {
                            val arr = json.parseToJsonElement(text).jsonArray
                            if (arr[0].jsonPrimitive.content == "OK" && arr[1].jsonPrimitive.content == event.id) arr[2].jsonPrimitive.content == "true" else null
                        }.getOrNull()
                    }.first()
                }
                session.send(buildJsonArray { add(JsonPrimitive("EVENT")); add(json.encodeToJsonElement(Event.serializer(), event)) }.toString())
                ok.await()
            }
        } catch (e: Exception) {
            false
        } finally {
            runCatching { session.close() }
        }
    } ?: false
}
