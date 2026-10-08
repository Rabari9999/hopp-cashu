package space.sovereigncast.hopp.directory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.crypto.Sha1
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.data.SubscribeResult
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.net.FeedFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Discover's sources (ADR 0008) against scripted directories. */
class DirectoryTest {
    private val now = 1_780_000_000L
    private val seen = mutableListOf<String>()
    private var fyydFails = false
    private val keysUsed = mutableListOf<String>()

    private fun pi(vararg feeds: String) = """{"status":"true","feeds":[${feeds.joinToString(",")}]}"""
    private fun piFeed(title: String, url: String, guid: String? = null, dead: Int = 0) =
        """{"id":1,"title":"$title","url":"$url","author":"A","artwork":"https://img.test/$title.jpg","dead":$dead${guid?.let { ",\"podcastGuid\":\"$it\"" } ?: ""}}"""

    private val show = """<?xml version="1.0"?><rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel>
        <title>Mine</title><podcast:guid>guid-mine</podcast:guid>
        <podcast:podroll>
          <podcast:remoteItem feedGuid="guid-friend"/>
          <podcast:remoteItem feedGuid="guid-mine"/>
          <podcast:remoteItem feedGuid="guid-unknown"/>
          <podcast:remoteItem feedGuid="guid-impostor"/>
        </podcast:podroll>
        <item><title>Ep</title><guid>e</guid><enclosure url="https://cdn.test/e.mp3" type="audio/mpeg"/></item>
        </channel></rss>"""

    private val http = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        seen += "${req.url.host}$path?${req.url.encodedQuery}"
        when {
            req.url.host == "api.podcastindex.org" -> {
                val date = req.headers["X-Auth-Date"]!!
                val key = req.headers["X-Auth-Key"]!!
                keysUsed += key
                val secret = "s3cr\$t"
                if (req.headers[HttpHeaders.Authorization] != Sha1.digest((key + secret + date).encodeToByteArray()).toHex())
                    return@MockEngine respondError(HttpStatusCode.Unauthorized)
                when {
                    path.endsWith("search/byterm") && req.url.parameters["q"] == "münzweg" -> respond(pi(piFeed("Münzweg", "https://muenzweg.test/feed.xml")))
                    path.endsWith("search/byterm") && req.url.parameters["q"] == "muenzweg" -> respond(pi())
                    path.endsWith("search/byterm") -> respond(pi(
                        piFeed("Alpha", "https://alpha.test/feed.xml", "guid-alpha"),
                        piFeed("Gone", "https://gone.test/feed.xml", dead = 1),
                    ))
                    path.endsWith("podcasts/byguid") -> when (req.url.parameters["guid"]) {
                        "guid-friend" -> respond("""{"status":"true","feed":${piFeed("Friend", "https://friend.test/rss", "guid-friend")}}""")
                        "guid-impostor" -> respond("""{"status":"true","feed":${piFeed("Other", "https://other.test/rss", "guid-something-else")}}""")
                        else -> respond("""{"status":"true","feed":[]}""")
                    }
                    else -> respondError(HttpStatusCode.NotFound)
                }
            }
            req.url.host == "api.fyyd.de" -> if (fyydFails) respondError(HttpStatusCode.ServiceUnavailable) else respond(
                """{"status":1,"data":[{"title":"Alpha","xmlURL":"http://www.Alpha.test/feed.xml/","author":"A"},{"title":"Beta","xmlURL":"https://beta.test/feed","imgURL":"https://img.test/b.jpg"}]}""")
            req.url.host == "itunes.apple.com" -> respond(
                """{"resultCount":2,"results":[{"collectionName":"Exclusive","artistName":"X"},{"collectionName":"Gamma","feedUrl":"https://gamma.test/rss","artistName":"G"}]}""")
            path == "/mine.xml" -> respond(show)
            else -> respondError(HttpStatusCode.NotFound)
        }
    })
    private val db = HoppDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.create(it) })
    private val settings = SettingsRepository(db)
    private val podcasts = PodcastRepository(db, FeedFetcher(http), { now })

    private fun directory(builtIn: PodcastIndexCredentials? = PodcastIndexCredentials("app-key", "s3cr\$t")) =
        DirectoryRepository(PodcastIndexClient(http, { now }), FyydClient(http), AppleClient(http), settings, podcasts, builtIn)

    private fun test(block: suspend () -> Unit) { runBlocking { block() } }

    @Test
    fun sha1MatchesTheStandardVectors() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Sha1.digest(ByteArray(0)).toHex())
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Sha1.digest("abc".encodeToByteArray()).toHex())
        assertEquals("84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            Sha1.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).toHex())
        assertEquals("34aa973cd4c4daa4f61eeb2bdbad27316534016f", Sha1.digest(ByteArray(1_000_000) { 'a'.code.toByte() }).toHex())
    }

    @Test
    fun thePodcastIndexAloneByDefaultSignedAndWithoutDeadFeeds() = test {
        val r = directory().search("alpha", valueOnly = false)
        assertEquals(listOf("Alpha"), r.shows.map { it.title }, "signed correctly, the dead feed left out")
        assertTrue(seen.none { "fyyd" in it || "itunes" in it }, "nobody else sees the search: $seen")
    }

    @Test
    fun switchedOnSourcesAreMergedEachShowOnce() = test {
        settings.update { it.copy(fyydSearch = true, appleSearch = true) }
        val r = directory().search("alpha", valueOnly = false)
        // fyyd's Alpha is the same feed (http, www, case, trailing slash); Apple's exclusive has no feed.
        assertEquals(listOf("Alpha", "Beta", "Gamma"), r.shows.map { it.title })
        assertEquals(listOf(Source.PodcastIndex, Source.Fyyd, Source.Apple), r.shows.map { it.source })
    }

    @Test
    fun theValueFilterAsksOnlyThePodcastIndex() = test {
        settings.update { it.copy(fyydSearch = true, appleSearch = true) }
        val r = directory().search("alpha", valueOnly = true)
        assertTrue(seen.single().contains("val=lightning"), "$seen")
        assertTrue(r.shows.all { it.hasValue == true })
    }

    @Test
    fun aSourceThatFailsIsNamedTheOthersStillAnswer() = test {
        settings.update { it.copy(fyydSearch = true) }
        fyydFails = true
        val r = directory().search("alpha", valueOnly = false)
        assertEquals(listOf(Source.Fyyd), r.failed)
        assertEquals(listOf("Alpha"), r.shows.map { it.title })
    }

    @Test
    fun aBuildWithoutKeySearchesOnlyWhatIsSwitchedOn() = test {
        assertTrue(directory(builtIn = null).search("alpha", valueOnly = false).unavailable)
        settings.update { it.copy(fyydSearch = true) }
        val r = directory(builtIn = null).search("alpha", valueOnly = false)
        assertEquals(listOf(Source.Fyyd, Source.Fyyd), r.shows.map { it.source })
        assertTrue(keysUsed.isEmpty())
    }

    @Test
    fun podrollRecommendsWhatIsNotSubscribedAndRealOnly() = test {
        val sub = podcasts.subscribe("https://pod.test/mine.xml")
        assertTrue(sub is SubscribeResult.Subscribed)
        // Itself, an unknown GUID and an index answer for a different GUID are left out.
        assertEquals(listOf("Friend"), directory().podroll().map { it.title })
    }

    @Test
    fun umlautsAreSearchedBothWays() = test {
        assertEquals(listOf("münzweg"), spellings("muenzweg"))
        assertEquals(listOf("muenzweg"), spellings("münzweg"))
        assertEquals(listOf("Grüsse"), spellings("Gruesse"))
        assertEquals(listOf("Strasse"), spellings("Straße"))
        assertTrue(spellings("bitcoin").isEmpty())
        // "muenzweg" alone finds nothing at the index — its other spelling does.
        assertEquals(listOf("Münzweg"), directory().search("muenzweg", valueOnly = false).shows.map { it.title })
    }
}
