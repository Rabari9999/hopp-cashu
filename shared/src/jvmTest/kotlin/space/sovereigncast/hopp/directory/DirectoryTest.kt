package space.sovereigncast.hopp.directory

import kotlinx.coroutines.flow.first
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
import space.sovereigncast.hopp.testDatabase

/** Discover's sources (ADR 0008) against scripted directories. */
class DirectoryTest {
    private val now = 1_780_000_000L
    private val seen = mutableListOf<String>()
    private var fyydFails = false
    private val keysUsed = mutableListOf<String>()

    private fun pi(vararg feeds: String) = """{"status":"true","feeds":[${feeds.joinToString(",")}]}"""
    private fun piFeed(title: String, url: String, guid: String? = null, dead: Int = 0, value: Boolean = false) =
        """{"id":1,"title":"$title","url":"$url","author":"A","artwork":"https://img.test/$title.jpg","dead":$dead${guid?.let { ",\"podcastGuid\":\"$it\"" } ?: ""}${if (value) ""","value":{"model":{"type":"lightning"},"destinations":[{"name":"N","address":"abc","split":100}]}""" else ""}}"""

    private val show = """<?xml version="1.0"?><rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel>
        <title>Mine</title><podcast:guid>guid-mine</podcast:guid>
        <itunes:category text="Education"><itunes:category text="How To"/></itunes:category><itunes:category text="Technology"/>
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
                    path.endsWith("search/byterm") && req.url.parameters["val"] == "lightning" && req.url.parameters["q"] == "Beta" -> respond(pi(
                        piFeed("Beta", "https://beta.test/feed.xml"),
                        piFeed("Beta", "https://beta-anchor.test/feed.xml"),
                    ))
                    path.endsWith("search/byterm") && req.url.parameters["val"] == "lightning" && req.url.parameters["q"] == "Bitcoin" -> respond(pi(
                        piFeed("Bitcoin Audible", "https://audible.test/feed.xml", value = true),
                    ))
                    path.endsWith("search/byterm") && req.url.parameters["val"] == "lightning" -> respond(pi(
                        piFeed("Alpha", "https://alpha.test/feed.xml", "guid-alpha"),
                        piFeed("Alpha", "https://valued.test/feed.xml", value = true),
                    ))
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
                    // As the index answers (checked 2026-10-27): numbers as numbers, the show inline.
                    path.endsWith("search/byperson") -> respond("""{"status":"true","count":3,"items":[
                        {"id":1,"title":"Hey Calso","guid":"g1","datePublished":1732317660,"duration":3497,"feedId":7,"feedTitle":"Signal","feedUrl":"https://signal.test/rss","feedImage":"https://img.test/s.jpg","feedAuthor":"S"},
                        {"id":2,"title":"No feed","guid":"g2","feedTitle":"Lost"},
                        {"id":3,"title":"Calso again","guid":"g3","duration":0,"feedTitle":"Signal","feedUrl":"https://signal.test/rss"}]}""")
                    path.endsWith("podcasts/trending") -> respond(pi(
                        """{"id":2,"title":"Hot","url":"https://hot.test/rss","categories":{"102":"Technology","55":"News"}}""",
                        """{"id":3,"title":"Copy","url":"https://copy.test/rss","duplicateOf":2}""",
                    ))
                    path.endsWith("recent/newvaluefeeds") -> respond(if (req.url.parameters["lang"] == "de")
                        pi("""{"id":4,"title":"Wert","url":"https://wert.test/rss","language":"de","categories":{"53":"Music"}}""")
                        else pi("""{"id":5,"title":"Value","url":"https://value.test/rss"}""", """{"id":4,"title":"Wert","url":"https://wert.test/rss"}"""))
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
    private val db = testDatabase()
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
        // The filter alone proves nothing (the index keeps stale value flags);
        // only a value object in the answer marks a show as boostable.
        assertEquals(listOf(null, true), r.shows.map { it.hasValue })
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
        assertEquals(listOf("Friend"), directory().podroll().map { it.show.title })
    }

    /** Maintainer 2026-10-26: episodes naming a person, with their show — none without a feed address. */
    @Test
    fun episodesWithAPerson() = test {
        val hits = directory().byPerson("Calso")!!
        assertEquals(listOf("g1", "g3"), hits.map { it.guid })
        assertEquals(PersonHit("https://signal.test/rss", "Signal", "https://img.test/s.jpg", "S", "g1", "Hey Calso", 1732317660, 3497), hits.first())
        assertEquals(null, hits.last().durationSeconds, "no length is no length, not 0 minutes")
        assertTrue(seen.any { "search/byperson" in it && "q=Calso" in it })
    }

    /** Device feedback 2026-10-19: Discover's popular and new value shows, with their categories. */
    @Test
    fun trendingAndNewValueFeedsWithTheirCategories() = test {
        val d = directory()
        val hot = d.trending("de", Category.Technology)
        assertEquals(listOf("Hot"), hot.map { it.title }, "a duplicate is left out")
        assertEquals(listOf(Category.Technology, Category.News), Category.of(hot.single().categories))
        assertTrue(seen.any { "podcasts/trending" in it && "lang=de" in it && "cat=Technology" in it })
        val asked = seen.size
        d.trending("de", Category.Technology)
        assertEquals(asked, seen.size, "kept for an hour")
        // "All languages": no language asked for.
        d.trending(null, null)
        assertTrue(seen.last().let { "podcasts/trending" in it && "lang=" !in it && "cat=" !in it })
        // Few in the listener's language: topped up from all, each once, all with value.
        val value = d.newValue("de", null, max = 4)
        assertEquals(listOf("Wert", "Value"), value.map { it.title })
        assertTrue(value.all { it.hasValue == true })
        // A show's own categories, from its feed.
        val id = (podcasts.subscribe("https://pod.test/mine.xml") as SubscribeResult.Subscribed).podcastId
        assertEquals(listOf("Education", "How To", "Technology"), podcasts.show(id).first()!!.categories)
        assertEquals(listOf(Category.Education, Category.Technology), Category.of(podcasts.show(id).first()!!.categories))
    }

    @Test
    fun indexAndAppleNamesComeToTheMainCategories() {
        assertEquals(Category.Health, Category.of("Mental Health"))
        assertEquals(Category.Health, Category.of("Fitness"))
        assertEquals(Category.Society, Category.of("Society & Culture"))
        assertEquals(Category.Education, Category.of("Self-Improvement"))
        assertEquals(Category.Sports, Category.of("Soccer"))
        assertEquals(Category.Film, Category.of("After-Shows"))
        assertEquals(Category.Technology, Category.of("Cryptocurrency"))
        assertEquals(null, Category.of("Unheard Of"))
        assertEquals(listOf(Category.Kids, Category.Health), Category.of(listOf("Kids", "Family", "Health", "Fitness", "Mental")))
    }

    @Test
    fun aBoostableVariantIsOfferedByTitleFromAnotherAddressValueFirst() = test {
        // The Lightning filter answers with the show's own feed and a variant
        // the index knows a value block of — the variant wins, the own
        // address is never offered.
        val alt = directory().boostableAlternative("Alpha", "https://alpha.test/feed.xml")
        assertEquals("https://valued.test/feed.xml", alt?.feedUrl)
        // A mere hit of the Lightning filter is the stale flag the badge no
        // longer trusts — without a confirmed value object nothing is offered
        // (review of 2026-10-19).
        assertEquals(null, directory().boostableAlternative("Beta", "https://beta.test/feed.xml"))
        // A similar, not equal title is another show, confirmed value block or
        // not — "Bitcoin" must not claim "Bitcoin Audible" (review of 2026-10-19).
        assertEquals(null, directory().boostableAlternative("Bitcoin", "https://bitcoin.test/feed.xml"))
        // Another show entirely matches nothing.
        assertEquals(null, directory().boostableAlternative("Münzweg", "https://muenzweg.test/feed.xml"))
        // A build without a key cannot ask.
        assertEquals(null, directory(builtIn = null).boostableAlternative("Alpha", "https://alpha.test/feed.xml"))
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
