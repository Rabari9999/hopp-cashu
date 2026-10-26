package space.sovereigncast.hopp.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What sharing offers and hands on (maintainer 2026-10-26: a choice, as in AntennaPod). */
class ShareLinksTest {
    @Test fun onlyAWebAddressIsAWebsite() {
        assertEquals("https://pod.test/e1", ShareLinks.web(" https://pod.test/e1 "))
        listOf(null, "", "pod.test/e1", "javascript:alert(1)", "ftp://pod.test/e1", "https://pod test/e1", "https://").forEach { assertNull(ShareLinks.web(it), it) }
    }

    @Test fun theAudioLinkStartsWhereAsked() {
        assertEquals("https://cdn.test/2.mp3", ShareLinks.audio("https://cdn.test/2.mp3", null))
        assertEquals("https://cdn.test/2.mp3#t=754", ShareLinks.audio("https://cdn.test/2.mp3", 754))
        // A fragment of its own is replaced, not doubled.
        assertEquals("https://cdn.test/2.mp3?x=1#t=90", ShareLinks.audio("https://cdn.test/2.mp3?x=1#old", 90))
    }

    @Test fun onlyWhatExistsIsOffered() {
        assertEquals(listOf(ShareKind.Hopp, ShareKind.Website, ShareKind.Feed), ShareLinks.kinds(show = true, website = true, audio = true, file = true))
        assertEquals(listOf(ShareKind.Hopp, ShareKind.Feed), ShareLinks.kinds(show = true, website = false, audio = false, file = false))
        assertEquals(listOf(ShareKind.Hopp, ShareKind.Website, ShareKind.AudioLink, ShareKind.AudioFile), ShareLinks.kinds(show = false, website = true, audio = true, file = true))
        assertEquals(listOf(ShareKind.Hopp, ShareKind.AudioLink), ShareLinks.kinds(show = false, website = false, audio = true, file = false))
    }

    @Test fun theLastChoiceComesFirstWhereItIsOne() {
        val episode = ShareLinks.kinds(show = false, website = false, audio = true, file = false)
        assertEquals(ShareKind.AudioLink, ShareLinks.first(episode, "AudioLink"))
        assertEquals(ShareKind.Hopp, ShareLinks.first(episode, "Feed"))
        assertEquals(ShareKind.Hopp, ShareLinks.first(episode, ""))
    }
}
