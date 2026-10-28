package space.sovereigncast.hopp.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.net.FeedFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import space.sovereigncast.hopp.testDatabase

/** Device feedback 2026-10-08: lists did not follow subscribe/unsubscribe,
 *  and a refresh put every episode into New. */
class LiveListsTest {
    private val nodesignal = javaClass.getResourceAsStream("/feeds/nodesignal-podhome.xml")!!.readBytes()
    private val http = HttpClient(MockEngine { respond(nodesignal) })  // no ETag, like PodHome
    private val db = testDatabase()
    private val podcasts = PodcastRepository(db, FeedFetcher(http), { 1000 })
    private val episodes = EpisodeRepository(db, http, { 1000 })

    private fun test(block: suspend CoroutineScope.() -> Unit) { runBlocking { block() } }

    @Test
    fun aRefreshWithoutEtagAddsNothingToNew() = test {
        val id = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
        assertEquals(1, episodes.inbox.first().size)
        assertEquals(0, podcasts.refresh(id))
        assertEquals(1, episodes.inbox.first().size, "a refresh of an unchanged feed filled New")
    }

    @Test
    fun theShowListFollowsSubscribeAndUnsubscribe() = test {
        val seen = mutableListOf<Int>()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) { podcasts.shows.collect { seen += it.size } }
        withTimeout(5_000) { while (seen.isEmpty()) kotlinx.coroutines.delay(10) }
        val id = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
        withTimeout(5_000) { while (seen.last() != 1) kotlinx.coroutines.delay(10) }
        podcasts.unsubscribe(id)
        withTimeout(5_000) { while (seen.last() != 0) kotlinx.coroutines.delay(10) }
        job.cancel()
        println("emissions: $seen")
    }
}
