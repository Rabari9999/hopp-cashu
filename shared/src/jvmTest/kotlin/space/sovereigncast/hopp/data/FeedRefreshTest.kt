package space.sovereigncast.hopp.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.testDatabase
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

/** Maintainer 2026-11-01: the browser missed a day's episodes the Android app had — nothing
 *  looked there but a pull, and a mouse cannot pull. */
class FeedRefreshTest {
    private val feed = javaClass.getResourceAsStream("/feeds/nodesignal-podhome.xml")!!.readBytes()
    private val fetches = AtomicInteger()
    private var now = 1000L
    private val db = testDatabase()
    private val podcasts = PodcastRepository(db, FeedFetcher(HttpClient(MockEngine { fetches.incrementAndGet(); respond(feed) })), { now })
    private val settings = SettingsRepository(db)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Stop : RuntimeException()

    @Test
    fun whileOpenTheFeedsAreLookedAtOnceTheIntervalIsOver() = runBlocking {
        podcasts.subscribe("https://pod.test/feed.xml")
        fetches.set(0)
        val sleeps = mutableListOf<Long>()
        val feeds = FeedRefresh(podcasts, settings, scope, { now }, whileOpen = true, sleep = { ms ->
            sleeps += ms; now += ms / 1000
            if (sleeps.size == 9) throw Stop()
        })
        assertFailsWith<Stop> { feeds.keepFresh() }
        // An hour (the default) after subscribing: six waits of ten minutes, one look, then a gap.
        assertEquals(listOf(600_000L, 600_000L, 600_000L, 600_000L, 600_000L, 600_000L, 300_000L, 600_000L, 600_000L), sleeps)
        assertEquals(1, fetches.get())
    }

    @Test
    fun aSecondAskWhileOneRunsJoinsIt() = runBlocking {
        podcasts.subscribe("https://pod.test/feed.xml")
        fetches.set(0)
        val gate = CompletableDeferred<Unit>()
        val feeds = FeedRefresh(podcasts, settings, scope, { now }, whileOpen = false, before = { gate.await() })
        val first = feeds.now()
        val second = feeds.now()
        assertSame(first, second)
        assertEquals(true, feeds.refreshing.value)
        gate.complete(Unit)
        withTimeout(10_000) { first.join() }
        assertFalse(feeds.refreshing.value)
        assertEquals(1, fetches.get())
    }

    /** The feeds in, the refresh is done: what comes after them runs on without the spinner (maintainer 2026-11-04). */
    @Test
    fun whatComesAfterTheFeedsIsNotWaitedFor() = runBlocking<Unit> {
        podcasts.subscribe("https://pod.test/feed.xml")
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val afters = AtomicInteger()
        val feeds = FeedRefresh(podcasts, settings, scope, { now }, whileOpen = false, after = { afters.incrementAndGet(); started.complete(Unit); gate.await() })
        withTimeout(10_000) { feeds.now().join() }
        assertFalse(feeds.refreshing.value)
        withTimeout(10_000) { started.await() }
        // A second refresh while the first one's "after" still runs: the feeds again, "after" not twice.
        withTimeout(10_000) { feeds.now().join() }
        assertEquals(1, afters.get())
        gate.complete(Unit)
    }
}
