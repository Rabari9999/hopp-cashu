package space.sovereigncast.hopp.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.net.FeedFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The library against an in-memory database and a scripted feed server. */
class RepositoryTest {
    private val nodesignal = javaClass.getResourceAsStream("/feeds/nodesignal-podhome.xml")!!.readBytes()
    private var feedBody = feed(2)
    private var etag = "\"v1\""
    private val requests = mutableListOf<String>()

    private fun feed(items: Int) = buildString {
        append("""<?xml version="1.0"?><rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel><title>Test Show</title>""")
        for (i in items downTo 1) append("""<item><title>Ep $i</title><guid>ep-$i</guid><pubDate>Mon, 0$i Jun 2026 06:00:00 +0000</pubDate><enclosure url="https://cdn.test/$i.mp3" length="1" type="audio/mpeg"/></item>""")
        append("</channel></rss>")
    }.encodeToByteArray()

    private val http = HttpClient(MockEngine { req ->
        requests += "${req.method.value} ${req.url} ${req.headers[HttpHeaders.IfNoneMatch] ?: ""}".trim()
        when (req.url.encodedPath) {
            "/feed.xml" -> if (req.headers[HttpHeaders.IfNoneMatch] == etag) respond("", HttpStatusCode.NotModified)
                else respond(feedBody, headers = headersOf(HttpHeaders.ETag, etag))
            "/old.xml" -> respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "/feed.xml"))
            "/temp.xml" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://pod.test/feed.xml"))
            "/nodesignal.xml" -> respond(nodesignal)
            else -> respond("", HttpStatusCode.NotFound)
        }
    })
    private val db = HoppDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.create(it) })
    private var now = 1_000L
    private val podcasts = PodcastRepository(db, FeedFetcher(http), { now })
    private val episodes = EpisodeRepository(db, http, { now })

    /** runBlocking that returns Unit — JUnit 4 rejects test methods with a value. */
    private fun test(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) { runBlocking { block() } }

    @Test
    fun subscribingPutsOnlyTheNewestEpisodeIntoNew() = test {
        val r = podcasts.subscribe("pod.test/feed.xml")
        assertIs<SubscribeResult.Subscribed>(r)
        assertEquals("Test Show", r.title)
        assertEquals(listOf("ep-2"), episodes.inbox.first().map { it.guid })
        assertEquals(2, episodes.ofPodcast(r.podcastId).first().size)
        assertIs<SubscribeResult.AlreadySubscribed>(podcasts.subscribe("https://pod.test/feed.xml"))
    }

    @Test
    fun anUnchangedFeedCostsA304AndANewItemLandsInNew() = test {
        val id = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
        assertEquals(0, podcasts.refresh(id))
        assertTrue(requests.last().endsWith("\"v1\""), "no conditional request: ${requests.last()}")

        feedBody = feed(3); etag = "\"v2\""
        assertEquals(1, podcasts.refresh(id))
        assertEquals(listOf("ep-3", "ep-2"), episodes.inbox.first().map { it.guid })
    }

    @Test
    fun listenerStateSurvivesARefresh() = test {
        val id = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
        val ep = episodes.ofPodcast(id).first().first { it.guid == "ep-1" }
        episodes.savePosition(ep.id, 90_000)
        feedBody = feed(3); etag = "\"v3\""
        podcasts.refresh(id)
        val again = episodes.item(ep.id)!!
        assertEquals(90_000, again.positionMs)
        assertEquals(listOf("ep-1"), episodes.inProgress.first().map { it.guid })
        episodes.setPlayed(ep.id, true)
        assertEquals(0, episodes.item(ep.id)!!.positionMs)
        assertTrue(episodes.inProgress.first().isEmpty())
    }

    /** Device feedback 2026-10-17: marked heard without playing is put away, not "Recently played";
     *  the badge counts what is not heard yet. */
    @Test
    fun onlyWhatWasPlayedIsRecentlyPlayed() = test {
        val id = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
        assertEquals(2, episodes.countsPerShow.first().getValue(id).unheard, "both unheard, though only one is in New")
        val ep2 = episodes.ofPodcast(id).first().first { it.guid == "ep-2" }
        episodes.savePosition(ep2.id, 60_000)
        episodes.setPlayed(ep2.id, true)
        episodes.setShowPlayed(id)
        assertEquals(listOf("ep-2"), episodes.recentlyPlayed(5).first().map { it.guid })
        assertEquals(0, episodes.countsPerShow.first().getValue(id).unheard)
    }

    @Test
    fun aPermanentRedirectMovesTheSubscriptionATemporaryOneDoesNot() = test {
        val moved = podcasts.subscribe("https://pod.test/old.xml") as SubscribeResult.Subscribed
        assertEquals("https://pod.test/feed.xml", podcasts.shows.first().single { it.id == moved.podcastId }.feedUrl)
        podcasts.unsubscribe(moved.podcastId)
        val temp = podcasts.subscribe("https://pod.test/temp.xml") as SubscribeResult.Subscribed
        assertEquals("https://pod.test/temp.xml", podcasts.shows.first().single { it.id == temp.podcastId }.feedUrl)
    }

    @Test
    fun theRealNodesignalFeedIsStoredAndReadBack() = test {
        val r = podcasts.subscribe("https://pod.test/nodesignal.xml") as SubscribeResult.Subscribed
        val list = episodes.ofPodcast(r.podcastId).first()
        assertEquals(325, list.size)
        val details = episodes.details(list.first().id)!!
        assertTrue(details.transcripts.isNotEmpty())
        assertEquals("e8df0b13-47de-544a-99b7-ec7cbd960a16", podcasts.podcastMeta(r.podcastId)!!.podcastGuid)
    }

    @Test
    fun unsubscribingRemovesEverything() = test {
        val id = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
        podcasts.unsubscribe(id)
        assertTrue(podcasts.shows.first().isEmpty())
        assertTrue(episodes.inbox.first().isEmpty())
        assertNull(episodes.item(1))
    }

    @Test
    fun opmlRoundTrip() = test {
        val opml = """<?xml version="1.0"?><opml version="2.0"><body>
            <outline text="Folder"><outline type="rss" text="A" xmlUrl="https://pod.test/feed.xml"/></outline>
            <outline type="rss" title="Broken" xmlURL="https://pod.test/missing.xml"/>
            <outline type="rss" text="A again" xmlUrl="https://pod.test/feed.xml"/>
        </body></opml>"""
        val results = podcasts.importOpml(opml).toMap()
        assertEquals(2, results.size)
        assertIs<SubscribeResult.Subscribed>(results["https://pod.test/feed.xml"])
        assertIs<SubscribeResult.Failed>(results["https://pod.test/missing.xml"])
        val exported = Opml.parse(podcasts.exportOpml())
        assertEquals(listOf(Opml.Entry("https://pod.test/feed.xml", "Test Show")), exported)
    }

    @Test
    fun pastedAddressesAreNormalised() {
        assertEquals("https://example.com/feed", FeedFetcher.normalize(" example.com/feed "))
        assertEquals("https://example.com/feed", FeedFetcher.normalize("feed://example.com/feed"))
        assertEquals("https://example.com/rss", FeedFetcher.normalize("podcast://example.com/rss"))
        assertEquals("http://example.com/rss", FeedFetcher.normalize("http://example.com/rss"))
        assertNull(FeedFetcher.normalize("ftp://example.com/rss"))
        assertNull(FeedFetcher.normalize("not a url"))
    }

    /** Device feedback 2026-10-14: an episode and every older one heard, as in
     *  Castamatic — the newer ones stay, and so do other shows. */
    @Test
    fun anEpisodeAndTheOlderOnesAreMarkedPlayed() = test {
        feedBody = feed(4)
        val id = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
        val list = episodes.ofPodcast(id).first()
        episodes.savePosition(list.first { it.guid == "ep-2" }.id, 90_000)
        episodes.setPlayedUpTo(list.first { it.guid == "ep-3" })
        val after = episodes.ofPodcast(id).first().associate { it.guid to it }
        assertEquals(setOf("ep-1", "ep-2", "ep-3"), after.values.filter { it.played }.map { it.guid }.toSet())
        assertFalse(after.getValue("ep-4").played, "a newer episode stays unheard")
        assertEquals(0L, after.getValue("ep-2").positionMs, "heard: back to the start, as one marked alone")
        assertTrue(episodes.inbox.first().none { it.guid != "ep-4" }, "heard ones leave New")
    }

    @Test
    fun aWholeShowIsMarkedPlayed() = test {
        feedBody = feed(3)
        val id = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
        episodes.setShowPlayed(id)
        assertTrue(episodes.ofPodcast(id).first().all { it.played })
        assertTrue(episodes.inbox.first().isEmpty())
    }
}
