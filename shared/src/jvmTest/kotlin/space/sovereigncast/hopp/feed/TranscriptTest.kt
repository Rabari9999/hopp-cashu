package space.sovereigncast.hopp.feed

import space.sovereigncast.hopp.model.TranscriptRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Transcripts as feeds serve them (ADR 0012, feature matrix). */
class TranscriptTest {
    private fun resource(name: String) = javaClass.getResource("/transcripts/$name")!!.readText()

    @Test
    fun podhomesVttSrtAndHtmlReadAlike() {
        val vtt = TranscriptParser.parse(resource("nodesignal-recap.vtt"), "text/vtt")
        val srt = TranscriptParser.parse(resource("nodesignal-recap.srt"), "application/x-subrip")
        val html = TranscriptParser.parse(resource("nodesignal-recap.html"), "text/html")
        val first = Cue(22_032, 28_732, null, "Herzlich willkommen bei NoSignal, deiner Bitcoin-Frequenz und der Monatszusammenfassung für den Monat September 2026.")
        assertEquals(first, vtt.first())
        assertEquals(first, srt.first())
        assertEquals(vtt.map { it.text }, srt.map { it.text })
        // PodHome's HTML marks whole seconds before each paragraph.
        assertEquals(Cue(22_000, null, null, first.text), html.first())
        assertEquals(vtt.size, html.size)
    }

    @Test
    fun voiceTagsNameTheSpeaker() {
        val vtt = TranscriptParser.parse("WEBVTT\n\n00:01.000 --> 00:02.500\n<v Karsten>Hallo &amp; willkommen</v>\n\n1:00:00.000 --> 1:00:01.000\nThorsten: Danke.\n")
        assertEquals(listOf(Cue(1_000, 2_500, "Karsten", "Hallo & willkommen"), Cue(3_600_000, 3_601_000, null, "Thorsten: Danke.")), vtt)
    }

    @Test
    fun jsonWordsJoinIntoSentences() {
        val json = """{"version":"1.0.0","segments":[
            {"speaker":"A","startTime":0.5,"endTime":0.9,"body":"Hallo"},
            {"speaker":"A","startTime":0.9,"endTime":1.4,"body":"zusammen."},
            {"speaker":"A","startTime":1.6,"endTime":2.0,"body":"Heute"},
            {"speaker":"B","startTime":2.1,"endTime":2.6,"body":"Moin!"}]}"""
        assertEquals(
            listOf(Cue(500, 1_400, "A", "Hallo zusammen."), Cue(1_600, 2_000, "A", "Heute"), Cue(2_100, 2_600, "B", "Moin!")),
            TranscriptParser.parse(json, "application/json"),
        )
    }

    @Test
    fun plainTextHasParagraphsWithoutTimes() {
        val cues = TranscriptParser.parse("Erster Absatz.\n\nZweiter\nAbsatz.", "text/plain")
        assertEquals(listOf("Erster Absatz.", "Zweiter\nAbsatz."), cues.map { it.text })
        assertNull(cues.first().startMs)
    }

    @Test
    fun theTimedFormatIsPreferred() {
        val refs = listOf(
            TranscriptRef("https://x/t.html", "text/html", "de", "transcript"),
            TranscriptRef("https://x/t.srt", "application/x-subrip", "de", "captions"),
            TranscriptRef("https://x/t.vtt", "text/vtt", "de", "captions"),
        )
        assertEquals("https://x/t.vtt", TranscriptParser.pick(refs)!!.url)
    }

    @Test
    fun theCueAtAPosition() {
        val cues = TranscriptParser.parse(resource("nodesignal-recap.vtt"), "text/vtt")
        assertEquals(-1, cues.indexAt(10_000), "before the first cue")
        assertEquals(0, cues.indexAt(22_032))
        assertEquals(1, cues.indexAt(30_000))
    }
}
