package space.sovereigncast.hopp.ui

import app.cash.sqldelight.async.coroutines.awaitAsList
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.testDatabase
import space.sovereigncast.hopp.ui.library.PreviewState
import space.sovereigncast.hopp.ui.library.ShowPreviewViewModel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Device feedback 2026-10-08: opening and subscribing felt slow. The show
 *  page loads the feed once, subscribing uses what it loaded. */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewSubscribeTest {
    private val nodesignal = javaClass.getResourceAsStream("/feeds/nodesignal-podhome.xml")!!.readBytes()
    private val fetches = AtomicInteger()
    private val http = HttpClient(MockEngine { fetches.incrementAndGet(); delay(300); respond(nodesignal) })
    private val db = testDatabase()
    private val podcasts = PodcastRepository(db, FeedFetcher(http), { 1000 })

    @BeforeTest fun main() = Dispatchers.setMain(Dispatchers.Unconfined)
    @AfterTest fun reset() = Dispatchers.resetMain()

    @Test
    fun subscribingFromTheShowPageFetchesTheFeedOnce() = runBlocking {
        val vm = ShowPreviewViewModel("https://pod.test/feed.xml", FeedFetcher(http), podcasts)
        // Pressed before the feed is even there: waits for it, does not fetch again.
        var opened: Long? = null
        vm.subscribe { opened = it }
        withTimeout(10_000) { while (opened == null) delay(10) }
        assertEquals(1, fetches.get(), "the feed was fetched more than once")
        assertEquals(PreviewState.Loaded::class, vm.state.value::class)
        assertEquals(325, db.episodeQueries.selectByPodcast(opened!!).awaitAsList().size)
    }
}
