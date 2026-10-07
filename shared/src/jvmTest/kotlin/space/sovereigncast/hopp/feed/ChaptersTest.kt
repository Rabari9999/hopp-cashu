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
