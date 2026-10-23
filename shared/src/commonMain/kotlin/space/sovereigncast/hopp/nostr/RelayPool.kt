package space.sovereigncast.hopp.nostr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * One connection per relay for a while — a sync round, say — instead of one per request
 * (device check 2026-10-23: a first round opened 13 connections to each relay in under four
 * seconds; relay.damus.io answered 503 from the third on, nos.lol 429 from the twelfth, and
 * only one relay of three took the round). Every request and event of [block] that goes
 * through a [RelayPublisher] to the same relay shares its connection; what the relay sends
 * reaches each of them, and each takes only its own answers (its subscription, its event's
 * `OK`), as on a connection of its own. One that drops is opened again by the next request.
 * Nested, the outer pool is used.
 */
suspend fun <T> withRelayPool(block: suspend () -> T): T {
    if (coroutineContext[RelayPool] != null) return block()
    return coroutineScope {
        // Its own supervisor: a connection that cannot be opened fails the request that asked for
        // it, as without the pool — not the whole of [block] (device check 2026-10-23: a 503 from
        // the pool's opening, a child of the caller, took the app down).
        val connections = SupervisorJob(coroutineContext[Job])
        val pool = RelayPool(CoroutineScope(coroutineContext + connections))
        try { withContext(pool) { block() } } finally { pool.closeAll(); connections.cancel() }
    }
}

/** The connections [withRelayPool] holds, by connector and address. */
internal class RelayPool(private val scope: CoroutineScope) : AbstractCoroutineContextElement(RelayPool) {
    companion object Key : CoroutineContext.Key<RelayPool>

    private val lock = Mutex()
    private val open = mutableMapOf<Pair<RelayConnector, String>, Deferred<Shared>>()

    /** The shared connection to [url], opened once — the first request that needs it waits for it. */
    suspend fun session(connector: RelayConnector, url: String): RelaySession {
        val key = connector to url
        val pending = lock.withLock {
            open[key]?.takeUnless { it.isCompleted && (it.isCancelled || runCatching { it.getCompleted().closed }.getOrDefault(true)) }
                ?: scope.async(start = CoroutineStart.LAZY) { Shared(connectRetrying(connector, url), scope) }.also { open[key] = it }
        }
        return try {
            pending.await().view()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not opened: the next request tries again.
            lock.withLock { if (open[key] === pending) open.remove(key) }
            throw e
        }
    }

    suspend fun closeAll() {
        val all = lock.withLock { open.values.toList().also { open.clear() } }
        all.forEach { d -> if (d.isCompleted && !d.isCancelled) runCatching { d.getCompleted().close() } else d.cancel() }
    }

    /** One connection, its messages handed to everyone listening; null after the last: it closed. */
    private class Shared(private val inner: RelaySession, scope: CoroutineScope) {
        private val messages = MutableSharedFlow<String?>(extraBufferCapacity = 256)
        private val sending = Mutex()
        @kotlin.concurrent.Volatile
        var closed = false
            private set
        private val reader: Job = scope.launch {
            try { inner.incoming.collect { messages.emit(it) } } catch (e: Exception) { if (e is CancellationException) throw e }
            finally { closed = true; messages.tryEmit(null) }
        }

        /** What a request sees: the connection's messages from now on; closing it leaves the connection open. */
        fun view(): RelaySession = object : RelaySession {
            // Closed before this one listened: it hears so at once, not only at its timeout.
            override val incoming: Flow<String> =
                messages.onSubscription { if (closed) emit(null) }.takeWhile { it != null }.map { it!! }
            override suspend fun send(message: String) {
                if (closed) throw IllegalStateException("relay connection closed")
                sending.withLock { inner.send(message) }
            }
            override suspend fun close() {}
        }

        suspend fun close() {
            reader.cancel()
            runCatching { inner.close() }
        }
    }
}

/**
 * A connection to [url]; when the relay turns it away as too many (HTTP 429, or 503 as
 * relay.damus.io answers then), tried again after a short wait — twice, 0.8 s and 1.6 s
 * (device check 2026-10-23). Anything else fails at once.
 */
internal suspend fun connectRetrying(connector: RelayConnector, url: String, waits: List<Long> = listOf(800, 1600)): RelaySession {
    var attempt = 0
    while (true) {
        try {
            return connector.connect(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (attempt >= waits.size || !e.isTooMany()) throw e
            delay(waits[attempt++])
        }
    }
}

/** Whether a failed connection was a relay saying "too many" (429) or "not now" (503) — the
 *  handshake's status, as OkHttp ("…but was '503 Service Unavailable'") and Ktor word it. */
internal fun Throwable.isTooMany(): Boolean =
    generateSequence(this) { it.cause }.any { e -> e.message?.let { Regex("""\b(429|503)\b""").containsMatchIn(it) } == true }
