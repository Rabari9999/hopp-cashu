package space.sovereigncast.hopp.playback

import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import space.sovereigncast.hopp.data.DownloadFiles
import space.sovereigncast.hopp.data.DownloadRepository
import space.sovereigncast.hopp.data.DownloadScheduler
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.QueueRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.data.SubscribeResult
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.net.FeedFetcher

/** What Android Auto browses (ADR 0012). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en")
class MediaTreeTest {
    private val feed = """<?xml version="1.0"?><rss version="2.0"><channel><title>Test Show</title>
        <item><title>Ep 2</title><guid>ep-2</guid><pubDate>Tue, 02 Jun 2026 06:00:00 +0000</pubDate><enclosure url="https://cdn.test/2.mp3" type="audio/mpeg"/></item>
        <item><title>Ep 1</title><guid>ep-1</guid><pubDate>Mon, 01 Jun 2026 06:00:00 +0000</pubDate><enclosure url="https://cdn.test/1.mp3" type="audio/mpeg"/></item>
        </channel></rss>"""
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val http = HttpClient(MockEngine { respond(feed) })
    private val db = HoppDatabase(AndroidSqliteDriver(HoppDatabase.Schema.synchronous(), context, null))
    private val podcasts = PodcastRepository(db, FeedFetcher(http), { 1000 })
    private val episodes = EpisodeRepository(db, http, { 1000 })
    private val queue = QueueRepository(db)
    private val downloads = DownloadRepository(db, http, { 1000 }, DownloadFiles(context.cacheDir.absolutePath), DownloadScheduler.None, SettingsRepository(db))
    private val tree = MediaTree(context, podcasts, episodes, queue, downloads)

    @Test
    fun theTreeHasNewUpNextDownloadsAndTheLibrary() = runBlocking {
        val showId = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
        val folders = tree.children(MediaTree.ROOT)
        assertEquals(listOf("New", "Up next", "Downloads", "Library"), folders.map { it.mediaMetadata.title.toString() })
        assertTrue(folders.all { it.mediaMetadata.isBrowsable == true })

        val new = tree.children(MediaTree.NEW)
        assertEquals(listOf("Ep 2"), new.map { it.mediaMetadata.title.toString() })
        assertTrue(new.single().mediaMetadata.isPlayable == true)

        val shows = tree.children(MediaTree.LIBRARY)
        assertEquals("Test Show", shows.single().mediaMetadata.title.toString())
        assertEquals(listOf("Ep 2", "Ep 1"), tree.children(shows.single().mediaId).map { it.mediaMetadata.title.toString() })

        val ep1 = episodes.ofPodcast(showId).first().single { it.title == "Ep 1" }
        queue.playLast(ep1.id)
        assertEquals(listOf("Ep 1"), tree.children(MediaTree.QUEUE).map { it.mediaMetadata.title.toString() })
    }

    @Test
    fun aPlayedIdResolvesToTheStreamAtItsPosition() = runBlocking {
        val showId = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
        val ep = episodes.ofPodcast(showId).first().single { it.title == "Ep 1" }
        episodes.savePosition(ep.id, 42_000)
        val (item, position) = tree.resolve(MediaTree.EP + ep.id)!!
        assertEquals(ep.id.toString(), item.mediaId)
        assertEquals("https://cdn.test/1.mp3", item.localConfiguration!!.uri.toString())
        assertEquals(42_000L, position)
        assertEquals(MediaTree.EP + ep.id, tree.lastPlayed())
    }
}
