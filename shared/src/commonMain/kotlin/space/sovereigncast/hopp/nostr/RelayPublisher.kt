package space.sovereigncast.hopp.nostr

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
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

    /** The relay's connection: the pool's within [withRelayPool], else one of its own — tried
     *  again when the relay says "too many" ([connectRetrying]). */
    private suspend fun open(url: String): RelaySession =
        kotlin.coroutines.coroutineContext[RelayPool]?.session(connector, url) ?: connectRetrying(connector, url)

    /**
     * The relays that answered `OK true`; one that fails or stays silent is left out. [settle]: once more than half
     * took it, the others get that long and no longer — what they do not take now, later versions bring them.
     */
    suspend fun publish(event: Event, relays: List<String>, settle: Duration? = null): Set<String> = coroutineScope {
        val urls = relays.distinct()
        val answers = Channel<Answer<String>>(Channel.UNLIMITED)
        val jobs = urls.map { url -> launch { answers.send(Answer(url.takeIf { publishTo(event, url) })) } }
        val took = mutableSetOf<String>()
        var until: TimeMark? = null
        var received = 0
        while (received < urls.size) {
            val left = until?.let { -it.elapsedNow() }
            val box = if (left == null) answers.receive() else withTimeoutOrNull(left) { answers.receive() } ?: break
            received++
            box.value?.let { took += it }
            if (settle != null && until == null && took.size * 2 > urls.size) until = TimeSource.Monotonic.markNow() + settle
        }
        jobs.forEach { it.cancel() }
        took
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
    data class Latest(val event: Event?, val complete: Boolean, val endedOn: Set<String> = emptySet())

    suspend fun latestChecked(kind: Int, author: String, relays: List<String>, settle: Duration? = null): Latest {
        // A relay that closed the request, or was cut off, did not answer to the end: not complete.
        val got = gather(relays, settle) { url -> latestOn(kind, author, url)?.let { url to it } }
        val answers = got.results.map { it.second }
        return Latest(answers.flatMap { it.events }.maxByOrNull { it.created_at }, got.complete && answers.all { it.ended },
            got.results.filter { it.second.ended }.map { it.first }.toSet())
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
    data class Checked(val events: List<Event>, val answered: Int, val asked: Int, val answeredBy: Set<String> = emptySet()) {
        /** Whether a majority of [these] relays answered to the end — of none asked, true. */
        fun majorityOf(these: List<String>): Boolean = these.distinct().let { it.isEmpty() || answeredBy.count { url -> url in it } * 2 > it.size }
    }

    /** [majorityFirst]: the settle time starts only once more than half of [relays] answered to the end, so
     *  what is held by a majority is always found — the rest are no longer waited for (maintainer 2026-11-09). */
    suspend fun queryChecked(filters: List<JsonObject>, relays: List<String>, settle: Duration? = null, majorityFirst: Boolean = false): Checked {
        val got = gather(relays, settle, majorityFirst) { url -> queryOn(filters, url, toTheEnd = true)?.let { url to it } }
        return Checked(got.results.flatMap { it.second }.filter { it.isValid() }.distinctBy { it.id }, got.results.size, relays.distinct().size, got.results.map { it.first }.toSet())
    }

    /** What [gather] came to; [complete] when every relay answered — none unreachable, none cut off by the settle time. */
    private class Gathered<T>(val results: List<T>, val complete: Boolean)

    /** [one] on every relay at once; what each answered — null from one that could not be reached.
     *  An answer of nothing is an answer too: it starts the settle time — with [majorityFirst], the answer
     *  that makes more than half. */
    private suspend fun <T : Any> gather(relays: List<String>, settle: Duration?, majorityFirst: Boolean = false, one: suspend (String) -> T?): Gathered<T> = coroutineScope {
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
            if (settle != null && until == null && (!majorityFirst || answered * 2 > urls.size)) until = TimeSource.Monotonic.markNow() + settle
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
        val pool = kotlin.coroutines.coroutineContext[RelayPool]
        if (pool?.isSilent(url) == true) { noteAnswer(url, RelayOp.Read, RelayAnswer.Unreachable(null)); return null }
        val found = mutableListOf<Event>()
        val started = TimeSource.Monotonic.markNow()
        var reached = false
        var ended = false
        // Why it did not end, for the sync page (RelayLog): the relay's own words, too much, or no connection.
        var closed: String? = null
        var cutOff = false
        var openError: String? = null
        var timedOut = false
        val finished = try {
            withTimeoutOrNull(timeout) {
                val session = runCatching { open(url) }.onFailure { if (it !is kotlinx.coroutines.CancellationException) openError = it.message ?: it::class.simpleName }.getOrNull() ?: return@withTimeoutOrNull
                reached = true
                try {
                    coroutineScope {
                        val sub = "hopp-" + secureRandomBytes(6).toHex()
                        var bytes = 0L
                        val done = async(start = CoroutineStart.UNDISPATCHED) {
                            session.incoming.mapNotNull { text ->
                                if (!text.answers(sub)) return@mapNotNull null
                                bytes += text.length
                                if (bytes > MAX_BYTES || found.size >= MAX_EVENTS || text.length > MAX_MESSAGE) { cutOff = true; return@mapNotNull false }
                                runCatching {
                                    val arr = json.parseToJsonElement(text).jsonArray
                                    if (arr.getOrNull(1)?.jsonPrimitive?.content != sub) return@runCatching null
                                    when (arr[0].jsonPrimitive.content) {
                                        "EVENT" -> {
                                            json.decodeFromJsonElement(Event.serializer(), arr[2]).takeIf { NostrFilter.matchesAny(it, filters) }?.let(found::add)
                                            null
                                        }
                                        "EOSE" -> true
                                        "CLOSED" -> { closed = (arr.getOrNull(2) as? JsonPrimitive)?.content.orEmpty(); false }
                                        else -> null
                                    }
                                }.getOrNull()
                            }.first()
                        }
                        try {
                            session.send(buildJsonArray { add(JsonPrimitive("REQ")); add(JsonPrimitive(sub)); filters.forEach { add(it) } }.toString())
                            ended = done.await()
                        } finally { session.closeSubscription(sub) }
                    }
                } catch (e: Exception) {
                    // What arrived before the failure still counts.
                    if (e is kotlinx.coroutines.CancellationException) timedOut = true
                } finally {
                    runCatching { session.close() }
                }
            } != null
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Left behind by the others' settle time (gather): no answer in time. Without even a connection by then: not waited for again.
            if (!reached && openError == null) pool?.noteSilent(url)
            withContext(NonCancellable) { noteAnswer(url, RelayOp.Read, if (reached) RelayAnswer.Silent else RelayAnswer.Unreachable(null)) }
            throw e
        }
        if (!finished && !reached && openError == null) pool?.noteSilent(url)
        // Noted also when the read was called off (the others' settle time): a relay left behind did not answer in time.
        val answer = when {
            ended -> RelayAnswer.Ok(started.elapsedNow().inWholeMilliseconds)
            !reached -> RelayAnswer.Unreachable(openError)
            closed != null -> RelayAnswer.Refused(closed!!.ifBlank { "closed" })
            cutOff -> RelayAnswer.Partial
            !finished || timedOut -> RelayAnswer.Silent
            else -> RelayAnswer.Partial
        }
        withContext(NonCancellable) { noteAnswer(url, RelayOp.Read, answer) }
        return found.toList().takeIf { reached && (ended || !toTheEnd) }
    }

    /**
     * The request's end sent however it ended — answered, timed out or cancelled: on a shared
     * connection ([withRelayPool]) the relay would go on answering it until the round is over
     * otherwise (delta audit 5 2026-10-24, RP-03). Briefly — a relay that does not take it is not waited for.
     */
    private suspend fun RelaySession.closeSubscription(sub: String) = withContext(NonCancellable) {
        withTimeoutOrNull(CLOSE_WAIT) { runCatching { send("""["CLOSE","$sub"]""") } }
    }

    /**
     * Whether this message answers subscription [sub] — told by its head ([RelayFrame]), before it is
     * read. Only those count against a request's limits; on a shared connection the others' answers
     * are theirs (delta audit 5 2026-10-24, RP-02: two large reads at once cut each other off). One of
     * its own too large to read ends the read as not to the end: passed over, the EOSE after it would
     * make the read look complete without it (delta audit 6 2026-10-24).
     */
    private fun String.answers(sub: String): Boolean =
        RelayFrame.head(this)?.let { (type, id) -> id == sub && type in ANSWERS } == true

    companion object {
        /** At most this many events from one relay for one request. */
        const val MAX_EVENTS = 5_000
        /** At most this much text from one relay for one request. */
        const val MAX_BYTES = 16L * 1024 * 1024
        /** A single message larger than this is passed over (an event holds 64 KiB of content). */
        const val MAX_MESSAGE = 512 * 1024
        /** What answers a subscription. */
        private val ANSWERS = setOf("EVENT", "EOSE", "CLOSED")
        private val CLOSE_WAIT = 1.seconds
        /** How long a relay that said "rate-limited" is given before the event goes to it once more. */
        val RATE_LIMIT_WAIT = 3.seconds
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
        if (kotlin.coroutines.coroutineContext[RelayPool]?.isSilent(url) == true) return@withTimeoutOrNull null
        val session = runCatching { open(url) }.getOrNull() ?: return@withTimeoutOrNull null
        try {
            coroutineScope {
                val sub = "hopp-" + secureRandomBytes(6).toHex()
                val found = mutableListOf<Event>()
                var bytes = 0L
                val done = async(start = CoroutineStart.UNDISPATCHED) {
                    session.incoming.mapNotNull { text ->
                        if (!text.answers(sub)) return@mapNotNull null
                        bytes += text.length
                        if (bytes > MAX_BYTES || found.size >= MAX_EVENTS || text.length > MAX_MESSAGE) return@mapNotNull false
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
                val ended = try {
                    session.send("""["REQ","$sub",{"kinds":[$kind],"authors":["$author"],"limit":1}]""")
                    done.await()
                } finally { session.closeSubscription(sub) }
                LatestAnswer(listOfNotNull(found.maxByOrNull { it.created_at }), ended)
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { session.close() }
        }
    }

    private suspend fun publishTo(event: Event, url: String): Boolean {
        if (kotlin.coroutines.coroutineContext[RelayPool]?.isSilent(url) == true) { noteAnswer(url, RelayOp.Send, RelayAnswer.Unreachable(null)); return false }
        var answer = try {
            publishOnce(event, url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Left behind by the others' settle time ([publish]): no answer in time.
            withContext(NonCancellable) { noteAnswer(url, RelayOp.Send, RelayAnswer.Silent) }
            throw e
        }
        // Turned away for sending too fast: once more after a pause (maintainer 2026-10-30, relays
        // refusing a long document's parts sent one after another).
        if (answer is RelayAnswer.Refused && Refusal.of(answer.reason) == Refusal.RateLimited) {
            delay(RATE_LIMIT_WAIT)
            answer = publishOnce(event, url)
        }
        noteAnswer(url, RelayOp.Send, answer)
        return answer is RelayAnswer.Ok
    }

    private suspend fun publishOnce(event: Event, url: String): RelayAnswer {
        val started = TimeSource.Monotonic.markNow()
        var reached = false
        var answer: RelayAnswer? = null
        val pool = kotlin.coroutines.coroutineContext[RelayPool]
        try {
            withTimeoutOrNull(timeout) {
                val session = runCatching { open(url) }.onFailure { if (it !is kotlinx.coroutines.CancellationException) answer = RelayAnswer.Unreachable(it.message ?: it::class.simpleName) }.getOrNull()
                    ?: return@withTimeoutOrNull
                reached = true
                try {
                    coroutineScope {
                        // Listen before sending, so a fast OK is not missed.
                        val ok = async(start = CoroutineStart.UNDISPATCHED) {
                            session.incoming.mapNotNull { text ->
                                // Only this event's OK is read; the rest passed over by its head.
                                if (RelayFrame.head(text) != ("OK" to event.id)) return@mapNotNull null
                                runCatching {
                                    val arr = json.parseToJsonElement(text).jsonArray
                                    if (arr[0].jsonPrimitive.content == "OK" && arr[1].jsonPrimitive.content == event.id) {
                                        if (arr[2].jsonPrimitive.content == "true") RelayAnswer.Ok(started.elapsedNow().inWholeMilliseconds)
                                        else RelayAnswer.Refused((arr.getOrNull(3) as? JsonPrimitive)?.content.orEmpty().ifBlank { "refused" })
                                    } else null
                                }.getOrNull()
                            }.first()
                        }
                        session.send(buildJsonArray { add(JsonPrimitive("EVENT")); add(json.encodeToJsonElement(Event.serializer(), event)) }.toString())
                        answer = ok.await()
                    }
                } catch (e: Exception) {
                    // The connection dropped before the answer.
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    answer = RelayAnswer.Unreachable(e.message)
                } finally {
                    runCatching { session.close() }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Left behind by the others' settle time, without even a connection by then: not waited for again.
            if (!reached && answer == null) pool?.noteSilent(url)
            throw e
        }
        if (answer == null && !reached) pool?.noteSilent(url)
        return answer ?: if (reached) RelayAnswer.Silent else RelayAnswer.Unreachable(null)
    }
}
