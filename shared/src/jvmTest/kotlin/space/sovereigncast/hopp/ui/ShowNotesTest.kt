package space.sovereigncast.hopp.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import space.sovereigncast.hopp.ui.components.showNotes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Show notes on the episode page (device feedback 2026-10-08, after Pocket Casts). */
class ShowNotesTest {
    private val jumps = mutableListOf<Long>()
    private fun notes(html: String) = showNotes(html, SpanStyle(), SpanStyle()) { jumps += it }

    @Test
    fun paragraphsListsAndEntitiesReadAsText() {
        val s = notes("<p>Heute&nbsp;über   <b>Nodes</b> &amp; mehr.</p><ul><li>Eins</li><li>Zwei</li></ul><p>Tsch&uuml;ss &#8211; bis bald</p>")
        assertEquals("Heute über Nodes & mehr.\n\n• Eins\n• Zwei\n\nTschüss – bis bald", s.text)
        assertTrue(s.spanStyles.any { s.text.substring(it.start, it.end) == "Nodes" }, "bold kept")
    }

    @Test
    fun linksAndBareUrlsAreTappable() {
        val s = notes("""<p>Mehr bei <a href="https://nodesignal.space/folge">Nodesignal</a> oder https://example.org/x.</p>""")
        val urls = s.getLinkAnnotations(0, s.length).map { (it.item as LinkAnnotation.Url).url to s.text.substring(it.start, it.end) }
        assertEquals(setOf("https://nodesignal.space/folge" to "Nodesignal", "https://example.org/x" to "https://example.org/x"), urls.toSet())
    }

    @Test
    fun timeMarksJumpToTheirPosition() {
        val s = notes("<p>(00:00) Intro<br>(12:34) Hauptteil<br>1:02:03 Ende, um 2026 nichts</p>")
        val marks = s.getLinkAnnotations(0, s.length).map { it.item }.filterIsInstance<LinkAnnotation.Clickable>()
        assertEquals(3, marks.size)
        marks.forEach { it.linkInteractionListener?.onClick(it) }
        assertEquals(listOf(0L, 754L, 3723L), jumps)
        assertEquals("(00:00) Intro\n(12:34) Hauptteil\n1:02:03 Ende, um 2026 nichts", s.text)
    }

    @Test
    fun plainTextKeepsItsLineBreaks() {
        assertEquals("Zeile eins\nZeile zwei", notes("Zeile eins\nZeile zwei").text)
    }

    @Test
    fun scriptsVanish() {
        assertEquals("Text", notes("<script>alert(1)</script><p>Text</p><style>p{}</style>").text)
    }

    /** Device feedback 2026-10-16 (Einundzwanzig #336): each list item wrapped in a
     *  paragraph read as a bullet alone on its line, the text two lines below. */
    @Test
    fun aParagraphInsideAListItemIsTheItemsText() {
        val s = notes("""<p>Themen</p><ul><li><p>Code “<a href="https://bitbox.swiss/einundzwanzig">einundzwanzig</a>” — 10%.</p></li><li><p>Besuche den <a href="https://einundzwanzig.shop/">Shop</a></p></li></ul><p>Weitere Links</p>""")
        assertEquals("Themen\n\n• Code “einundzwanzig” — 10%.\n• Besuche den Shop\n\nWeitere Links", s.text)
        val numbered = notes("<ol><li><p>Eins</p><p>noch eins</p></li><li><p>Zwei</p></li></ol>")
        assertEquals("1. Eins\nnoch eins\n2. Zwei", numbered.text)
    }

    /** Fountain ends each comment with the episode's link and its nevent (device feedback 2026-10-16). */
    @Test
    fun aCommentLosesFountainsTailAndShowsNames() {
        val key = "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e"
        val text = space.sovereigncast.hopp.ui.components.commentText(
            "Great at 12:34, nostr:npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg! See https://example.com/x \n\n" +
                "https://fountain.fm/episode/yUUIctmvqiIO2fiWKLKI  nostr:nevent1qqsdtvfv5w5mu0g5c9kmxfhsy9wxq0xsr6pzz2pwr9wy5rpvjkeg2dqnm5uw6",
            { if (it == key) "Alice" else "?" }, SpanStyle(), SpanStyle(), SpanStyle(),
        ) { jumps += it }
        assertEquals("Great at 12:34, @Alice! See https://example.com/x", text.text)
        assertTrue(text.getLinkAnnotations(0, text.length).any { (it.item as? LinkAnnotation.Url)?.url == "https://example.com/x" })
        // A Fountain link inside the text is the listener's own: it stays.
        assertEquals("Listen https://fountain.fm/episode/abc too", space.sovereigncast.hopp.ui.components.commentText(
            "Listen https://fountain.fm/episode/abc too", { "?" }, SpanStyle(), SpanStyle(), SpanStyle()) {}.text)
    }
}
