package space.sovereigncast.hopp.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChaptersTest {
    @Test
    fun aRealPodHomeChaptersFile() {
        val text = javaClass.getResourceAsStream("/feeds/nodesignal-chapters.json")!!.readBytes().decodeToString()
        val chapters = ChaptersParser.parse(text)
        assertEquals(5, chapters.size)
        assertEquals("Einstieg", chapters.first().title)
        // PodHome writes "img": "" — empty means no image, not a broken URL.
        assertNull(chapters.first().imageUrl)
    }

    /** echtgeld.tv #494 (Podigee), as it is written: plain line ends inside a paragraph. */
    private val echtgeld = """<p><strong>Die einzelnen Themen und besprochenen Wertpapiere im Überblick:</strong>
(00:00:00) Willkommen zu echtgeld.tv: <a href="https://clickbiz.de/ref/egtv-podcast">https://clickbiz.de/ref/egtv-podcast</a>
(00:00:33) Der Disclaimer
(00:01:01) <strong>Paypal</strong> (WKN: A14R7U)
(00:25:12) <strong>People Inc.</strong> (ehemals IAC) (WKN: A3CQZU)
(00:41:55) <strong>Angi</strong> (WKN: A4142Q)
(00:45:58) <strong>Netflix</strong> (WKN: 552484)</p>
<p>🐦 X: <a href="https://x.com/aktien_guide">https://x.com/aktien_guide</a></p>"""

    @Test
    fun aListInTheNotesIsChapters() {
        val chapters = NotesChapters.parse(echtgeld, 2935)
        assertEquals(listOf(0.0, 33.0, 61.0, 1512.0, 2515.0, 2758.0), chapters.map { it.startSeconds })
        assertEquals("Willkommen zu echtgeld.tv", chapters[0].title)
        assertEquals("https://clickbiz.de/ref/egtv-podcast", chapters[0].url)
        assertEquals("People Inc. (ehemals IAC) (WKN: A3CQZU)", chapters[3].title)
        assertNull(chapters[3].url)
    }

    @Test
    fun theNotesWriteTheirListInManyWays() {
        val titles = listOf("Intro", "Haupt & Teil", "Ende")
        for (notes in listOf(
            "<ul><li>00:00 Intro</li><li>12:34 – Haupt &amp; Teil</li><li>1:02:03 - Ende</li></ul>",
            "[00:00] Intro<br>[12:34] Haupt &amp; Teil<br/>[1:02:03] Ende",
            "<p>00:00: Intro</p><p>12:34: Haupt &amp; Teil</p><p>1:02:03: Ende</p>",
            "<p><a href=\"https://a.test/#t=0\">00:00</a> Intro</p><p><a href=\"https://a.test/#t=754\">12:34</a> Haupt &amp; Teil</p><p><a href=\"https://a.test/#t=3723\">1:02:03</a> Ende</p>",
            "1. 00:00 Intro\n2. 12:34 Haupt & Teil\n3. 1:02:03 Ende",
            "- 00:00 Intro\n- 12:34 Haupt & Teil\n- 1:02:03 Ende",
            "Intro (00:00)\nHaupt & Teil (12:34)\nEnde (1:02:03)",
            "Intro 00:00<br>Haupt &amp; Teil - 12:34<br>Ende 1:02:03",
        )) {
            val chapters = NotesChapters.parse(notes, 4000)
            assertEquals(titles, chapters.map { it.title }, notes)
            assertEquals(listOf(0.0, 754.0, 3723.0), chapters.map { it.startSeconds }, notes)
        }
    }

    @Test
    fun aMarkInPassingIsNoChapter() {
        for (notes in listOf(
            null, "", "<p>Keine Marken hier.</p>",
            // In the running text.
            "Bei 12:34 sagt er es, bei 23:45 nochmal, und bei 45:01 wieder.",
            "<p>Bei 12:34 sagt er es.</p><p>Bei 23:45 nochmal.</p><p>Bei 45:01 wieder.</p>",
            // Two are no list.
            "00:00 Intro\n12:34 Ende",
            // Falling: no order to jump along.
            "45:00 A\n30:00 B\n10:00 C",
            // Times of day.
            "18:00 Uhr Einlass\n19:30 Uhr Beginn\n22:00 Uhr Ende",
            // No titles.
            "00:00\n12:34\n23:45",
        )) assertEquals(emptyList(), NotesChapters.parse(notes, 4000), notes)
        // Past the episode's end: not this cut's list.
        assertEquals(emptyList(), NotesChapters.parse("00:00 A\n50:00 B\n1:30:00 C", 3000))
    }

    @Test
    fun aStrayLineBesideTheListIsLeftOut() {
        val notes = "59:00 Hinweis auf letzte Woche\n<p>Heute:</p>00:00 A\n10:00 B\n20:00 C\n30:00 D"
        assertEquals(listOf("A", "B", "C", "D"), NotesChapters.parse(notes, 4000).map { it.title })
    }

    @Test
    fun podloveWritesItsTimesInThreeWays() {
        assertEquals(3723.5, ChaptersParser.normalPlayTime("01:02:03.500"))
        assertEquals(123.0, ChaptersParser.normalPlayTime("02:03"))
        assertEquals(90.5, ChaptersParser.normalPlayTime("90.5"))
        assertNull(ChaptersParser.normalPlayTime(""))
        assertNull(ChaptersParser.normalPlayTime("soon"))
        assertNull(ChaptersParser.normalPlayTime("1:2:3:4"))
    }

    private fun episodeWith(extra: String) = FeedParser.parse("""<?xml version="1.0"?><rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0" xmlns:psc="http://podlove.org/simple-chapters"><channel><title>S</title>
<item><title>E</title><guid>e</guid>$extra</item></channel></rss>""".encodeToByteArray()).episodes.single()

    @Test
    fun theFeedsChapterFileIsFoundUnderEitherName() {
        assertEquals("https://a.test/c.json", episodeWith("""<podcast:chapters url="https://a.test/c.json" type="application/json+chapters"/>""").chaptersUrl)
        // Podigee.
        assertEquals("https://a.test/c.json", episodeWith("""<podcast:chapters href="https://a.test/c.json" type="application/json+chapters"/>""").chaptersUrl)
        assertNull(episodeWith("""<podcast:chapters type="application/json+chapters"/>""").chaptersUrl)
    }

    @Test
    fun podloveChaptersAreReadFromTheFeed() {
        val chapters = episodeWith("""<psc:chapters version="1.2">
<psc:chapter start="00:25:12.000" title="Zwei" href="javascript:alert(1)"/>
<psc:chapter start="00:00:33.000" title="Eins" href="https://a.test/x" image="https://a.test/x.jpg"/>
<psc:chapter title="Ohne Zeit"/>
</psc:chapters>""").chapters
        assertEquals(listOf("Eins", "Zwei"), chapters.map { it.title })
        assertEquals(listOf(33.0, 1512.0), chapters.map { it.startSeconds })
        assertEquals("https://a.test/x", chapters[0].url)
        assertEquals("https://a.test/x.jpg", chapters[0].imageUrl)
        assertNull(chapters[1].url)
    }

    private val index = ChapterIndex(listOf(
        Chapter(0.0, "Intro"),
        Chapter(60.0, "Image only", imageUrl = "https://example.test/a.jpg", inToc = false),
        Chapter(120.0, "Topic", imageUrl = "https://example.test/b.jpg"),
        Chapter(600.0, "Outro"),
    ))

    @Test
    fun nonTocChaptersAreNoStopsButTheirImagesShow() {
        assertEquals(listOf("Intro", "Topic", "Outro"), index.stops.map { it.title })
        assertEquals("Intro", index.current(90_000)?.title)
        assertEquals("https://example.test/a.jpg", index.imageAt(90_000))
        assertNull(index.imageAt(30_000))
    }

    /** Device feedback 2026-10-11, as in Nodesignal E297: images only in
     *  chapters 2 and 5 — chapters 3 and 4 show the episode's cover again. */
    @Test
    fun aChapterWithoutAnImageShowsTheCoverAgain() {
        val e297 = ChapterIndex(listOf(
            Chapter(22.0, "Intro und Hacking"),
            Chapter(1689.0, "Axel Hamburg Hardware Show", imageUrl = "https://example.test/axel.jpg"),
            Chapter(2584.0, "Die Hackday Jury"),
            Chapter(3850.0, "Casa Münzweg"),
            Chapter(4668.0, "Finale und Auswertung", imageUrl = "https://example.test/finale.jpg"),
        ))
        assertNull(e297.imageAt(600_000))
        assertEquals("https://example.test/axel.jpg", e297.imageAt(2_000_000))
        assertNull(e297.imageAt(2_600_000))
        assertNull(e297.imageAt(4_000_000))
        assertEquals("https://example.test/finale.jpg", e297.imageAt(4_700_000))
        // The same for a chapter after an image-only entry: Outro follows Topic's b.jpg.
        assertNull(index.imageAt(700_000))
        assertEquals("https://example.test/b.jpg", index.imageAt(200_000))
    }

    @Test
    fun nextAndPrevious() {
        assertEquals(120_000L, index.nextStartMs(30_000))
        assertNull(index.nextStartMs(700_000))
        // More than 3 s into a chapter: back to its start …
        assertEquals(120_000L, index.previousStartMs(200_000))
        // … within the first 3 s: to the chapter before.
        assertEquals(0L, index.previousStartMs(121_000))
        assertEquals(0L, index.previousStartMs(1_000))
    }

    @Test
    fun remainingTimeAndBoundaries() {
        assertEquals(20_000L, index.remainingInChapterMs(100_000, 900_000))
        assertEquals(100_000L, index.remainingInChapterMs(800_000, 900_000))
        assertEquals(listOf(120f / 900f, 600f / 900f), index.boundaryFractions(900_000))
    }
}
