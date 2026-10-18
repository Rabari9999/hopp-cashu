package space.sovereigncast.hopp.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.ui.library.episodeSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The file's size from the feed in the episode lists (user request 2026-10-18):
 *  download now, stream, or wait for Wi-Fi. */
class EpisodeSizeTest {
    private var length = "48000000"
    private val http = HttpClient(MockEngine { req ->
        if (req.url.encodedPath == "/feed.xml") respond("""<?xml version="1.0"?><rss version="2.0"><channel><title>Show</title>
            <item><title>Big</title><guid>big</guid><enclosure url="https://cdn.test/big.mp3" length="$length" type="audio/mpeg"/></item>
            <item><title>Unknown</title><guid>none</guid><enclosure url="https://cdn.test/none.mp3" type="audio/mpeg"/></item>
            <item><title>Placeholder</title><guid>tiny</guid><enclosure url="https://cdn.test/tiny.mp3" length="1" type="audio/mpeg"/></item>
            </channel></rss>""")
        else respond("", HttpStatusCode.NotFound)
    })
    private val now = 1_000_000L
    private val db = HoppDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.create(it) })
    private val podcasts = PodcastRepository(db, FeedFetcher(http), { now })
    private val episodes = EpisodeRepository(db, http, { now })

    private suspend fun subscribe() = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
    // Not on the device: subscribing queues the newest episode for download.
    private suspend fun byTitle(podcastId: Long) = episodes.ofPodcast(podcastId).first()
        .associate { it.title to it.copy(downloadState = DownloadState.NONE) }

    @Test
    fun theFeedsSizeShowsForAnEpisodeNotOnTheDevice() = runBlocking {
        val show = byTitle(subscribe())
        assertEquals(48_000_000L, show.getValue("Big").enclosureLength)
        assertEquals("45 MB", episodeSize(show.getValue("Big")))
        assertNull(show.getValue("Unknown").enclosureLength, "the feed states none")
        assertNull(episodeSize(show.getValue("Unknown")))
        assertNull(episodeSize(show.getValue("Placeholder")), "one byte is no file's size")
        // On the device, or on its way: the row says that instead.
        assertNull(episodeSize(show.getValue("Big").copy(downloadState = DownloadState.DONE)))
        assertNull(episodeSize(show.getValue("Big").copy(downloadState = DownloadState.QUEUED)))
        assertEquals("< 1 MB", episodeSize(show.getValue("Big").copy(enclosureLength = 600_000)))
    }

    @Test
    fun aRefreshUpdatesTheSize() = runBlocking {
        val id = subscribe()
        length = "1610612736"
        podcasts.refresh(id)
        assertEquals("1.5 GB", episodeSize(byTitle(id).getValue("Big")))
    }

    /** Episodes stored before schema version 10 have their size in meta_json only. */
    @Test
    fun episodesFromBeforeTheColumnGetTheirSizeFromTheStoredFeed() = runBlocking {
        val id = subscribe()
        db.episodeQueries.setEnclosureLength(null, byTitle(id).getValue("Big").id)
        assertNull(byTitle(id).getValue("Big").enclosureLength)
        episodes.fillEnclosureLengths()
        assertEquals(48_000_000L, byTitle(id).getValue("Big").enclosureLength)
        assertEquals(0, db.episodeQueries.withoutLength(10).executeAsList().size, "every row read once")
    }
}
