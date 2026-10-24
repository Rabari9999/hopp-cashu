package space.sovereigncast.hopp.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import space.sovereigncast.hopp.sync.SyncDevice
import space.sovereigncast.hopp.sync.SyncOutcome
import space.sovereigncast.hopp.value.FakeRelay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * One connection per relay for a round, and a relay that says "too many" asked again
 * (device check 2026-10-23: 13 connections to each relay in under four seconds on a first
 * round; relay.damus.io answered 503 from the third on, nos.lol 429 from the twelfth).
 */
class RelayPoolTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nsec = Bech32.encode("nsec", KeyPair.generate().secretKey)
    private val url = "wss://relay.test"

    @AfterTest fun stop() = scope.cancel()

    /** Counts the connections to each relay. */
    private class Counting(private val inner: RelayConnector) : RelayConnector {
        val opened = ConcurrentHashMap<String, AtomicInteger>()
        override suspend fun connect(url: String): RelaySession {
            opened.getOrPut(url) { AtomicInteger() }.incrementAndGet()
            return inner.connect(url)
        }
    }

    private fun event(content: String) = Event.sign(KeyPair.generate(), 1, content, createdAt = 1_790_000_000)

    @Test
    fun aFirstRoundOpensOneConnectionToEachRelay() = runBlocking<Unit> {
        val relays = Counting(FakeRelay())
        val phone = SyncDevice(relays, scope, nsec)
        phone.podcasts.subscribe("https://pod.test/nodesignal.xml")
        relays.opened.clear()
        val status = phone.sync.enable()
        assertNull(status.problem)
        assertTrue(relays.opened.isNotEmpty())
        // Without the pool: a connection for every read and every event — 13 a relay on the device.
        relays.opened.forEach { (relay, n) -> assertEquals(1, n.get(), "connections to $relay") }
    }

    @Test
    fun aRelaySayingTooManyIsAskedAgain() = runBlocking<Unit> {
        val relay = FakeRelay()
        val tries = AtomicInteger()
        val busy = RelayConnector { u ->
            if (tries.incrementAndGet() <= 2) throw java.net.ProtocolException("Expected HTTP 101 response but was '429 Too Many Requests'")
            relay.connect(u)
        }
        val e = event("hello")
        assertEquals(setOf(url), RelayPublisher(busy).publish(e, listOf(url)))
        assertEquals(3, tries.get())
        // Once more than that, or anything but "too many": not taken, and no waiting for nothing.
        tries.set(0)
        val down = RelayConnector { throw java.net.ConnectException("Connection refused") }
        assertEquals(emptySet(), RelayPublisher(down).publish(event("again"), listOf(url)))
        val gone = AtomicInteger()
        val unavailable = RelayConnector { gone.incrementAndGet(); throw java.net.ProtocolException("Expected HTTP 101 response but was '503 Service Unavailable'") }
        assertEquals(emptySet(), RelayPublisher(unavailable).publish(event("later"), listOf(url)))
        assertEquals(3, gone.get())
    }

    @Test
    fun aDroppedConnectionIsOpenedAgainForTheNextRequest() = runBlocking<Unit> {
        val relay = FakeRelay()
        val kept = mutableListOf<Event>()
        relay.listeners += { kept += it }
        val relays = Counting(relay)
        val publisher = RelayPublisher(relays, timeout = 3.seconds)
        withRelayPool {
            assertEquals(setOf(url), publisher.publish(event("one"), listOf(url)))
            // A read the relay drops the connection on (FakeRelay closes it on every REQ).
            relay.readsFail = true
            assertTrue(publisher.query(listOf(kotlinx.serialization.json.buildJsonObject { }), listOf(url)).isEmpty())
            relay.readsFail = false
            assertEquals(setOf(url), publisher.publish(event("two"), listOf(url)))
        }
        assertEquals(2, relays.opened[url]!!.get())
        assertEquals(2, kept.size)
    }

    /** Requests waiting on a connection that drops hear so at once, not only at their timeout. */
    @Test
    fun aDroppedConnectionEndsWhatWaitsOnIt() = runBlocking<Unit> {
        val relay = FakeRelay()
        val publisher = RelayPublisher(relay, timeout = 30.seconds)
        val took = withRelayPool {
            assertEquals(setOf(url), publisher.publish(event("first"), listOf(url)))
            relay.readsFail = true
            val started = System.nanoTime()
            val read = publisher.queryChecked(listOf(kotlinx.serialization.json.buildJsonObject { }), listOf(url))
            assertEquals(0, read.answered)
            (System.nanoTime() - started) / 1_000_000
        }
        assertTrue(took < 5_000, "waited $took ms for a closed connection")
    }

    /** A relay that cannot be reached at all within a pool: its requests come to nothing, the rest
     *  goes on — the failure stays with the request (device check 2026-10-23: it took the app down). */
    @Test
    fun aRelayThatCannotBeReachedFailsOnlyItsRequests() = runBlocking<Unit> {
        val relay = FakeRelay()
        val down = "wss://down.test"
        val mixed = RelayConnector { u ->
            if (u == down) throw java.net.ProtocolException("Expected HTTP 101 response but was '503 Service Unavailable'")
            relay.connect(u)
        }
        val publisher = RelayPublisher(mixed)
        val took = withRelayPool {
            val first = publisher.publish(event("one"), listOf(url, down))
            val read = publisher.queryChecked(listOf(kotlinx.serialization.json.buildJsonObject { }), listOf(url, down))
            first to read.answered
        }
        assertEquals(setOf(url) to 1, took)
    }

    // ── delta audit 5 2026-10-24 ────────────────────────────────────────────

    /** A connection whose relay answers what [answer] says to each request, and keeps what it was sent. */
    private class Scripted(private val answer: (sub: String, all: List<String>, out: Channel<String>) -> Unit = { _, _, _ -> }) : RelayConnector {
        val sent = CopyOnWriteArrayList<String>()
        @Volatile var opened = 0
        override suspend fun connect(url: String): RelaySession = object : RelaySession {
            init { opened++ }
            val out = Channel<String>(Channel.UNLIMITED)
            val subs = mutableListOf<String>()
            override val incoming: Flow<String> = out.consumeAsFlow()
            override suspend fun send(message: String) {
                sent += message
                val arr = Json.parseToJsonElement(message).jsonArray
                if (arr[0].jsonPrimitive.content != "REQ") return
                val all = synchronized(subs) { subs += arr[1].jsonPrimitive.content; subs.toList() }
                answer(arr[1].jsonPrimitive.content, all, out)
            }
            override suspend fun close() { out.close() }
        }
    }

    /** RP-01: a relay that drops the connection right after a burst — as many messages as a request
     *  may have waiting, or one more or less. The end reached the requests waiting on it only while the
     *  shared buffer had room for it; with 256 waiting they waited for their timeout. */
    @Test
    fun aConnectionDroppedAfterABurstEndsWhatWaitsOnIt() = runBlocking<Unit> {
        for (burst in listOf(0, 255, 256, 257, 1_000)) {
            val relay = Scripted { _, _, out -> repeat(burst) { out.trySend("""["NOTICE","busy $it"]""") }; out.close() }
            val publisher = RelayPublisher(relay, timeout = 20.seconds)
            val started = System.nanoTime()
            val read = withRelayPool { publisher.queryChecked(listOf(buildJsonObject { }), listOf(url)) }
            val took = (System.nanoTime() - started) / 1_000_000
            assertEquals(0, read.answered)
            assertTrue(took < 5_000, "after a burst of $burst: waited $took ms for a closed connection")
        }
    }

    /** RP-02: two large reads at once on one connection, each well within its limit — together above
     *  it. Each counted the other's answers too, and neither was answered to its end. */
    @Test
    fun twoLargeReadsAtOnceEachKeepTheirOwnLimit() = runBlocking<Unit> {
        val big = event("x".repeat(64_000))
        val frame = { sub: String -> buildJsonArray { add("EVENT"); add(sub); add(Json.encodeToJsonElement(Event.serializer(), big)) }.toString() }
        // Once both asked: 132 copies to each, in turn — 8.5 million characters each, 17 million on the connection.
        val relay = Scripted { _, all, out ->
            if (all.size == 2) {
                repeat(132) { all.forEach { out.trySend(frame(it)) } }
                all.forEach { out.trySend("""["EOSE","$it"]""") }
            }
        }
        val publisher = RelayPublisher(relay, timeout = 20.seconds)
        // Started in the pool's own scope: an outer scope's async runs without the pool.
        val reads = withRelayPool {
            coroutineScope { List(2) { async { publisher.queryChecked(listOf(buildJsonObject { }), listOf(url)) } }.awaitAll() }
        }
        assertEquals(1, relay.opened)
        reads.forEach { assertEquals(1, it.answered); assertEquals(listOf(big.id), it.events.map { e -> e.id }) }
    }

    /** RP-03: a request that times out, or is cancelled, on a shared connection: the relay is told to
     *  stop answering it — not only when the round ends and the connection closes. */
    @Test
    fun aRequestThatTimesOutOrIsCancelledIsClosed() = runBlocking<Unit> {
        val relay = Scripted()
        val publisher = RelayPublisher(relay, timeout = 1.seconds)
        withRelayPool {
            assertEquals(0, publisher.queryChecked(listOf(buildJsonObject { }), listOf(url)).answered)
            assertEquals(RelayPublisher.Latest(null, false), publisher.latestChecked(1, "a".repeat(64), listOf(url)))
            coroutineScope {
                val cancelled = launch { publisher.query(listOf(buildJsonObject { }), listOf(url)) }
                delay(200)
                cancelled.cancelAndJoin()
            }
            val bySub = relay.sent.map { Json.parseToJsonElement(it).jsonArray }
                .groupBy({ it[1].jsonPrimitive.content }, { it[0].jsonPrimitive.content })
            assertEquals(3, bySub.size)
            bySub.forEach { (sub, sent) -> assertEquals(listOf("REQ", "CLOSE"), sent, sub) }
        }
        assertEquals(1, relay.opened)
    }

    // ── delta audit 6 2026-10-24 ────────────────────────────────────────────

    /** RP-02 again: the subscription was looked for in a message's first 100 characters. An answer of
     *  its own, padded with spaces or with the subscription escaped, was passed over, and the EOSE after
     *  it made the read look complete and empty; a stranger's message naming it in its content counted
     *  against its limit. Read as JSON reads them now — and one of its own too large to read is no
     *  longer passed over: the read is not complete. */
    @Test
    fun ownAnswersAreKnownHoweverTheyAreWritten() = runBlocking<Unit> {
        val e = event("hello")
        val body = Json.encodeToString(Event.serializer(), e)
        fun read(answer: (sub: String) -> List<String>) = runBlocking {
            val relay = Scripted { sub, _, out -> answer(sub).forEach { out.trySend(it) }; out.trySend("""["EOSE","$sub"]""") }
            withRelayPool { RelayPublisher(relay, timeout = 5.seconds).queryChecked(listOf(buildJsonObject { }), listOf(url)) }
        }
        for (frame in listOf<(String) -> String>(
            { sub -> """["EVENT","$sub",$body]""" },
            { sub -> " ".repeat(100) + """["EVENT","$sub",$body]""" },
            { sub -> """[ "EVENT" ,${" ".repeat(100)}"\u0068${sub.drop(1)}" , $body ]""" },
        )) {
            val got = read { sub -> listOf(frame(sub)) }
            assertEquals(1, got.answered, frame("hopp-x").take(40))
            assertEquals(listOf(e.id), got.events.map { it.id })
        }
        // Strangers naming it, more than its limit in all: not counted.
        val stranger = event("x")
        val got = read { sub ->
            val padding = "x".repeat(RelayPublisher.MAX_MESSAGE - 200)
            List(33) { """["EVENT","foreign",{"content":"\"$sub\"","pad":"$padding"}]""" } + """["NOTICE","$sub"]""" +
                """["EVENT","$sub",${Json.encodeToString(Event.serializer(), stranger)}]"""
        }
        assertEquals(1, got.answered)
        assertEquals(listOf(stranger.id), got.events.map { it.id })
        // Its own, too large to read: not complete.
        val tooLarge = read { sub -> listOf("""["EVENT","$sub",{"pad":"${"x".repeat(RelayPublisher.MAX_MESSAGE)}"}]""") }
        assertEquals(0, tooLarge.answered)
    }
}
