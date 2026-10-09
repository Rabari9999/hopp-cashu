package space.sovereigncast.hopp.live

import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.net.createHttpClient
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.sovereigncast.ListenerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Nodesignal's real instance, read only. Runs only with HOPP_LIVE_SOVEREIGNCAST=1. */
class SovereigncastLiveTest {
    @Test
    fun nodesignalsInstanceReads() = runBlocking {
        if (System.getenv("HOPP_LIVE_SOVEREIGNCAST") != "1") return@runBlocking
        val api = ListenerApi(createHttpClient(AppInfo("0.0.1", "JVM", "Test")))
        val base = "https://pm.nodesignal.space"
        val guid = "e8df0b13-47de-544a-99b7-ec7cbd960a16"
        assertTrue("v1" in api.meta(base).apiVersions)
        val show = api.show(base, guid)
        println("show: ${show.title}, ${show.capabilities}")
        assertEquals(guid, show.podcastGuid)
        val round = api.releaseBoosting(base, guid).round
        println("round: ${round?.episode?.title} ${round?.raisedSats}/${round?.goalSats} sats, ${round?.satsPerMinute} sats/min, out ${round?.publishAt}")
        val suggestions = api.suggestions(base, guid).suggestions
        println("suggestions: ${suggestions.size}, first: ${suggestions.firstOrNull()?.title?.take(60)} (${suggestions.firstOrNull()?.voteCount} votes)")
        val board = api.leaderboard(base, guid)
        println("leaderboard ${board.month}: ${board.topBoosts.size} top, ${board.recentBoosts.size} recent, ${board.alltimeBoosters.size} all-time; first ${board.topBoosts.firstOrNull()?.amountSats} sats")
    }
}
