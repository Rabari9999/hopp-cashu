package space.sovereigncast.hopp.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/** The corpus of ADR 0017: real feeds plus hand-made broken ones
 *  (src/jvmTest/resources/feeds/README.md). */
class FeedCorpusTest {

    private fun load(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/feeds/$name")) { "missing corpus file $name" }.readBytes()

    @Test
    fun theNodesignalFeedOnPodHome() {
        val (feed, took) = measureTimedValue { FeedParser.parse(load("nodesignal-podhome.xml")) }
        println("nodesignal-podhome.xml: ${feed.episodes.size} episodes in $took, warnings=${feed.warnings}")
        val p = feed.podcast
        assertEquals("e8df0b13-47de-544a-99b7-ec7cbd960a16", p.podcastGuid)
        assertEquals("de", p.language)
        assertEquals("podcast", p.medium)
        assertTrue(p.usesPodping)
        assertEquals("FREQ=WEEKLY", p.updateFrequency?.rrule)
        assertTrue(p.podroll.size >= 4, "podroll: ${p.podroll}")
        assertTrue(p.trailers.isNotEmpty())
        assertTrue(p.txt.any { it.purpose == "nostr" })
        assertEquals("keysend", p.value?.method)
        assertEquals(325, feed.episodes.size)
        assertTrue(feed.warnings.isEmpty(), "warnings: ${feed.warnings}")

        val withLnaddress = feed.episodes.first { ep -> ep.value?.recipients?.any { it.type == "lnaddress" } == true }
        val lnRecipient = withLnaddress.value!!.recipients.first { it.type == "lnaddress" }
        assertEquals("lnurl_callback", lnRecipient.customKey)

        val music = feed.episodes.first { ep -> ep.value?.timeSplits?.isNotEmpty() == true }
        val split = music.value!!.timeSplits.first()
        assertNotNull(split.remoteItem, "time split without remote item")
        assertEquals("music", split.remoteItem.medium)

        val latest = feed.episodes.first()
        assertNotNull(latest.publishedAt)
        assertNotNull(latest.enclosure)
        assertTrue(latest.transcripts.any { it.type == "text/vtt" })
        assertNotNull(latest.season)
    }

    @Test
    fun aSelfHostedSovereigncastFeed() {
        val feed = FeedParser.parse(load("sovereigncast-selfhosted.xml"))
        assertEquals(22, feed.episodes.size)
        assertNotNull(feed.podcast.podcastGuid)
        assertEquals("podcast", feed.podcast.medium)
        val ep = feed.episodes.first()
        assertNotNull(ep.season)
        assertNotNull(ep.episodeNumber)
        assertTrue(feed.episodes.any { it.socialInteracts.isNotEmpty() })
        assertTrue(feed.warnings.isEmpty(), "warnings: ${feed.warnings}")
    }

    @Test
    fun htmlEntitiesAndABareAmpersand() {
        val feed = FeedParser.parse(load("broken-entities.xml"))
        println("broken-entities: ${feed.podcast.title} | ${feed.podcast.description} | ${feed.warnings}")
        assertEquals("Tom & Jerry Show", feed.podcast.title)
        assertEquals("Fragen & Antworten öffentlich – Q&A live", feed.podcast.description)
        assertTrue(feed.warnings.isEmpty(), "warnings: ${feed.warnings}")
        assertEquals(2, feed.episodes.size, "warnings: ${feed.warnings}")
        assertEquals("Gast: Müller — © 2026", feed.episodes[0].description)
    }

    /** Code review 2026-10-17, M14: an "&" inside CDATA is text as it stands. */
    @Test
    fun anAmpersandInCdataStaysAsItIs() {
        val feed = FeedParser.parse("""<?xml version="1.0"?><rss version="2.0"><channel><title><![CDATA[Tom & Jerry]]></title>
            <description>Q&A live</description><link><![CDATA[https://x.test/?a=1&b=2]]></link>
            <item><title><![CDATA[Fragen & Antworten]]></title><guid>1</guid></item></channel></rss>""")
        assertEquals("Tom & Jerry", feed.podcast.title)
        assertEquals("Q&A live", feed.podcast.description)
        assertEquals("https://x.test/?a=1&b=2", feed.podcast.link)
        assertEquals("Fragen & Antworten", feed.episodes.single().title)
    }

    @Test
    fun parsingTheBigFeedAgainIsFast() {
        val bytes = load("nodesignal-podhome.xml")
        repeat(3) { FeedParser.parse(bytes) } // warm-up
        val took = (1..5).map { measureTimedValue { FeedParser.parse(bytes) }.duration }.sorted()[2]
        println("nodesignal-podhome.xml warm median: $took")
        assertTrue(took.inWholeMilliseconds < 2000, "parsing 2.3 MB took $took")
    }

    @Test
    fun utf8DeclaredButLatin1() {
        val feed = FeedParser.parse(load("broken-encoding.xml"))
        assertEquals("Grüße aus Köln", feed.podcast.title)
        assertTrue(feed.warnings.any { "Windows-1252" in it }, "${feed.warnings}")
    }

    @Test
    fun latin1Declared() {
        val feed = FeedParser.parse(load("latin1-declared.xml"))
        assertEquals("Grüße aus Köln", feed.podcast.title)
        assertTrue(feed.warnings.isEmpty(), "${feed.warnings}")
    }

    @Test
    fun namespaceVariants() {
        val feed = FeedParser.parse(load("namespace-variants.xml"))
        assertEquals("11111111-2222-5333-8444-555555555555", feed.podcast.podcastGuid)
        assertEquals("02abc", feed.podcast.value?.recipients?.single()?.address)
        assertEquals("https://example.test/cover.jpg", feed.podcast.imageUrl)
        assertEquals(2, feed.episodes[1].season)
        assertEquals(7.0, feed.episodes[1].episodeNumber)
    }

    @Test
    fun missingFields() {
        val feed = FeedParser.parse(load("missing-fields.xml"))
        val guids = feed.episodes.map { it.guid }
        assertEquals(listOf("https://example.test/a.mp3", "text-1", "no-title"), guids)
        assertEquals(null, feed.episodes[2].enclosure?.length)
        assertEquals(null, feed.episodes[2].enclosure?.type)
    }

    @Test
    fun whitespaceBeforeTheDeclaration() {
        val feed = FeedParser.parse(load("leading-whitespace.xml"))
        assertEquals("Whitespace first", feed.podcast.title)
        assertEquals(1, feed.episodes.size)
    }

    @Test
    fun aTruncatedDownloadKeepsTheCompleteItems() {
        val feed = FeedParser.parse(load("truncated.xml"))
        println("truncated: ${feed.episodes.map { it.guid }} ${feed.warnings}")
        assertEquals(listOf("ep-1", "ep-2"), feed.episodes.map { it.guid })
        assertTrue(feed.warnings.isNotEmpty())
    }

    @Test
    fun htmlInDescriptions() {
        val feed = FeedParser.parse(load("html-descriptions.xml"))
        assertEquals("<p>Hallo <b>Welt</b> &amp; mehr</p>", feed.episodes[0].description)
        assertEquals("<p>Escaped <i>HTML</i></p>", feed.episodes[1].description)
    }

    @Test
    fun rfc822Variants() {
        val expected = 1780380000L // 2026-06-02T06:00:00Z
        for (s in listOf("Tue, 02 Jun 2026 06:00:00 +0000", "Tue, 2 Jun 2026 06:00:00 GMT", "2 Jun 2026 06:00 Z",
                         "Tue, 02 Jun 2026 08:00:00 +02:00", "Tue, 02 Jun 2026 02:00:00 EDT", "Tue, 02 June 26 06:00:00 UT")) {
            assertEquals(expected, Rfc822.parse(s), s)
        }
        assertEquals(null, Rfc822.parse("gestern"))
        assertEquals(3600L, parseDurationSeconds("1:00:00"))
        assertEquals(3600L, parseDurationSeconds("60:00"))
        assertEquals(3600L, parseDurationSeconds("3600"))
    }
}
