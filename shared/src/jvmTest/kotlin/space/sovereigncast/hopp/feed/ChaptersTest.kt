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
