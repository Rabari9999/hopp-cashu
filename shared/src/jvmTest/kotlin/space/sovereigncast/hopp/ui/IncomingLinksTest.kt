package space.sovereigncast.hopp.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** "Open in Hopp" (docs/tasks/open-in-hopp-app-link.md): which links carry a show. */
class IncomingLinksTest {
    private val feed = "https://serve.podhome.fm/rss/e8df0b13-47de-544a-99b7-ec7cbd960a16"
    private val encoded = "https%3A%2F%2Fserve.podhome.fm%2Frss%2Fe8df0b13-47de-544a-99b7-ec7cbd960a16"

    @Test fun theAppLinkCarriesTheFeed() = assertEquals(feed, IncomingLinks.feedFrom("https://www.sovereigncast.space/open?feed=$encoded"))

    @Test fun theSchemeToo() = assertEquals(feed, IncomingLinks.feedFrom("hopp://subscribe?feed=$encoded"))

    @Test fun aFeedWithItsOwnQueryKeepsIt() =
        assertEquals("https://pod.test/feed.xml?token=a&b=c", IncomingLinks.feedFrom("hopp://subscribe?feed=" + "https%3A%2F%2Fpod.test%2Ffeed.xml%3Ftoken%3Da%26b%3Dc"))

    @Test fun nothingElseOpensAShow() {
        listOf(
            "https://www.sovereigncast.space/open",             // no feed
            "https://www.sovereigncast.space/open?feed=",       // empty
            "https://www.sovereigncast.space/open?feed=%20",    // blank
            "https://www.sovereigncast.space/open?feed=javascript%3Aalert(1)",
            "https://www.sovereigncast.space/open?feed=file%3A%2F%2F%2Fsdcard%2Fx",
            "https://www.sovereigncast.space/open?feed=https%3A%2F%2F",
            "https://www.sovereigncast.space/opener?feed=$encoded", // another page
            "https://evil.test/open?feed=$encoded",                 // another host
            "http://www.sovereigncast.space/open?feed=$encoded",    // not https
            "hopp://play?feed=$encoded",                            // another action
            "nostr+walletconnect://abc?relay=wss://r.test",
            "not a link",
        ).forEach { assertNull(IncomingLinks.feedFrom(it), it) }
    }

    /** Sharing (device feedback 2026-10-12): what Hopp shares, Hopp reads back. */
    @Test fun aSharedLinkCarriesEpisodeAndStart() {
        val link = IncomingLinks.shareLink(feed, "ep guid/1?&", 754)
        assertEquals("https://www.sovereigncast.space/open?feed=$encoded&episode=ep+guid%2F1%3F%26&t=754", link)
        assertEquals(ShowLink(feed, "ep guid/1?&", 754), IncomingLinks.linkFrom(link))
        assertEquals(ShowLink(feed, "g", null), IncomingLinks.linkFrom("hopp://subscribe?feed=$encoded&episode=g"))
    }

    @Test fun aShowLinkHasNeitherEpisodeNorStart() {
        assertEquals("https://www.sovereigncast.space/open?feed=$encoded", IncomingLinks.shareLink(feed))
        assertEquals(ShowLink(feed), IncomingLinks.linkFrom(IncomingLinks.shareLink(feed, startSeconds = 0)))
    }

    @Test fun aBadStartOrEpisodeIsLeftOut() {
        listOf("t=-5", "t=abc", "t=999999999", "t=", "episode=" + "x".repeat(600)).forEach { extra ->
            assertEquals(ShowLink(feed), IncomingLinks.linkFrom("https://www.sovereigncast.space/open?feed=$encoded&$extra"), extra)
        }
    }
}
