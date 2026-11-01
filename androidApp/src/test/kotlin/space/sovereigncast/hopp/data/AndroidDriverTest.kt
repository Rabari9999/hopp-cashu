package space.sovereigncast.hopp.data

import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.net.FeedFetcher

/** The repositories on Android's SQLite (device feedback 2026-10-08). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AndroidDriverTest {
    private val feed = """<?xml version="1.0"?><rss version="2.0"><channel><title>Test Show</title>
        <item><title>Ep 3</title><guid>ep-3</guid><pubDate>Wed, 03 Jun 2026 06:00:00 +0000</pubDate><enclosure url="https://cdn.test/3.mp3" type="audio/mpeg"/></item>
        <item><title>Ep 2</title><guid>ep-2</guid><pubDate>Tue, 02 Jun 2026 06:00:00 +0000</pubDate><enclosure url="https://cdn.test/2.mp3" type="audio/mpeg"/></item>
        <item><title>Ep 1</title><guid>ep-1</guid><pubDate>Mon, 01 Jun 2026 06:00:00 +0000</pubDate><enclosure url="https://cdn.test/1.mp3" type="audio/mpeg"/></item>
        </channel></rss>"""
    private val http = HttpClient(MockEngine { respond(feed) })
    private val db = HoppDatabase(AndroidSqliteDriver(HoppDatabase.Schema.synchronous(), ApplicationProvider.getApplicationContext(), "test.db"))
    private val podcasts = PodcastRepository(db, FeedFetcher(http), { 1000 })
    private val episodes = EpisodeRepository(db, http, { 1000 })

    @Test
    fun subscribingFillsNewWithTheNewestOnlyAndARefreshKeepsItSo() = runBlocking {
        val r = podcasts.subscribe("https://pod.test/feed.xml")
        val id = (r as SubscribeResult.Subscribed).podcastId
        println("podcast id $id, episodes of it: ${episodes.ofPodcast(id).first().size}, inbox: ${episodes.inbox.first().map { it.title }}")
        assertEquals(3, episodes.ofPodcast(id).first().size)
        assertEquals(listOf("Ep 3"), episodes.inbox.first().map { it.title })
        assertEquals(0, podcasts.refresh(id))
        assertEquals(listOf("Ep 3"), episodes.inbox.first().map { it.title })
    }

    @Test
    fun theListsFollowSubscribeAndUnsubscribe() = runBlocking {
        val shows = mutableListOf<Int>()
        val inbox = mutableListOf<Int>()
        val a = launch(Dispatchers.Default) { podcasts.shows.collect { shows += it.size } }
        val b = launch(Dispatchers.Default) { episodes.inbox.collect { inbox += it.size } }
        withTimeout(5_000) { while (shows.isEmpty() || inbox.isEmpty()) delay(10) }
        val id = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
        withTimeout(5_000) { while (shows.last() != 1 || inbox.last() != 1) delay(10) }
        podcasts.unsubscribe(id)
        withTimeout(5_000) { while (shows.last() != 0 || inbox.last() != 0) delay(10) }
        a.cancel(); b.cancel()
        println("shows $shows, inbox $inbox")
    }
}
