package space.sovereigncast.hopp.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.directory.DirectoryRepository
import space.sovereigncast.hopp.directory.PodcastIndexCredentials
import space.sovereigncast.hopp.di.Platform
import space.sovereigncast.hopp.di.sharedModule
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.platform.PlaybackItem
import space.sovereigncast.hopp.platform.PlaybackState

private const val FEED = """<?xml version="1.0"?><rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel><title>Test Show</title>
<podcast:funding url="https://fund.test/">Unterstütze uns</podcast:funding>
<podcast:person role="host">Thorsten</podcast:person>
<podcast:value type="lightning" method="keysend"><podcast:valueRecipient name="Host" type="node" address="02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" split="100"/></podcast:value>
<item><title>Ep 2</title><guid>ep-2</guid><pubDate>Tue, 02 Jun 2026 06:00:00 +0000</pubDate><enclosure url="https://cdn.test/2.mp3" type="audio/mpeg"/><description><![CDATA[<p>Mehr bei <a href="https://x.test">X</a>.</p><p>(12:34) Hauptteil</p>]]></description><podcast:person role="host" group="cast" href="https://primal.net/p/karsten">Karsten</podcast:person><podcast:transcript url="https://cdn.test/2.vtt" type="text/vtt" language="de"/><podcast:soundbite startTime="754" duration="30">Der Kern</podcast:soundbite></item>
<item><title>Ep 1</title><guid>ep-1</guid><pubDate>Mon, 01 Jun 2026 06:00:00 +0000</pubDate><enclosure url="https://cdn.test/1.mp3" type="audio/mpeg"/><podcast:chapters url="https://cdn.test/1.json" type="application/json+chapters"/></item>
</channel></rss>"""

private class FakeEngine : PlaybackEngine {
    private val s = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = s
    override fun load(item: PlaybackItem, playWhenReady: Boolean) { s.value = PlaybackState(item.id, playWhenReady, positionMs = item.startPositionMs) }
    override fun play() { s.value = s.value.copy(isPlaying = true) }
    override fun pause() { s.value = s.value.copy(isPlaying = false) }
    override fun seekTo(positionMs: Long) { s.value = s.value.copy(positionMs = positionMs) }
    override fun setSpeed(speed: Float) { s.value = s.value.copy(speed = speed) }
    override fun release() = Unit
}

private const val SC_GUID = "e8df0b13-47de-544a-99b7-ec7cbd960a16"
private const val SC_FEED = """<?xml version="1.0"?><rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel>
<title>Nodesignal</title><podcast:guid>e8df0b13-47de-544a-99b7-ec7cbd960a16</podcast:guid>
<podcast:txt purpose="sovereigncast">https://pm.test</podcast:txt>
<item><title>E296</title><guid>e296</guid><pubDate>Tue, 02 Jun 2026 06:00:00 +0000</pubDate><enclosure url="https://cdn.test/296.mp3" type="audio/mpeg"/></item>
</channel></rss>"""

private var votes = 12
/** The NWC explainer the tour links to is down (device feedback 2026-10-11). */
private var nwcDown = false
private const val TRANSCRIPT = "WEBVTT\n\n00:00:01.000 --> 00:00:05.000\n<v Karsten>Willkommen zur zweiten Folge.\n\n00:12:34.000 --> 00:12:40.000\n<v Karsten>Jetzt kommt der Hauptteil.\n"

/** A month with ten top boosts and a long all-time list — longer than the screen. */
private val LEADERBOARD = buildString {
    append("""{"month":"2026-10","top_boosts":[""")
    append((1..10).joinToString(",") { r ->
        val (sender, message) = if (r == 1) "" to "Gemeinsam basteln" else "Booster $r" to "Danke für Folge $r, das war wirklich eine ausgesprochen gute Folge!"
        """{"rank":$r,"amount_sats":${4231 - r * 10},"sender":"$sender","message":"$message","episode":{"episode_guid":"e296","title":"E296"}}"""
    })
    append("""],"recent_boosts":[{"amount_sats":21,"sender":"z","message":"","episode":{"episode_guid":"e296","title":"E296"}}],"alltime_boosters":[""")
    append((1..30).joinToString(",") { r ->
        val name = if (r == 1) "Blaubär" else "Hörer $r"
        """{"rank":$r,"sender":"$name","trend":"same","prev_rank":$r,"legendary":${r < 4},"medals":{"gold":${30 - r},"silver":1,"bronze":0}}"""
    })
    append("""],"poll_after_seconds":300}""")
}
private const val SUGGESTION_TEXT = "Eine ganze Folge über BIP110: was es will, wer dafür ist und wer nicht."
private var statusCalls = 0

/** A Sovereigncast instance as the Listener API answers (listener-v1.yaml). */
private fun io.ktor.client.engine.mock.MockRequestHandleScope.scInstance(path: String) = when {
    path == "/api/listener/meta" -> respond("""{"api_versions":["v1"],"instance":{"id":"i","software":"sovereigncast","version":"dev"}}""")
    path == "/api/listener/v1/shows/by-guid/$SC_GUID" -> respond("""{"podcast_guid":"$SC_GUID","title":"Nodesignal","feed_url":"https://pod.test/sc.xml",
        "capabilities":{"release_boosting":true,"boost2unlock":false,"halving_clip":true,"suggestions":true,"leaderboard":true,"top_episodes":false,"payment_metadata":true},
        "amounts":{"suggestion_min_sats":21},"poll_after_seconds":3600}""")
    path.endsWith("/release-boosting") -> respond("""{"round":{"episode":{"episode_guid":"e297","episode_nr":297,"title":"E297 - BTC++ Berlin","description":"Jan-Paul, Thorsten & Calso waren zu Gast.","image_url":null},
        "goal_sats":15120,"raised_sats":210,"sats_per_minute":21,"publish_at":"2026-10-09T21:50:00Z","chain":{"block_sats":210,"blocks":72,"mined_blocks":1,"halving_block":36},
        "clip":{"unlocked":false,"at_block":36}},"poll_after_seconds":60}""")
    path.endsWith("/suggestions") -> respond("""{"suggestions":[{"id":32,"title":"BIP110","description":"$SUGGESTION_TEXT","username":"Anonymous","amount_sats":2226,"vote_count":$votes,"created_at":"2026-07-06T11:39:40Z"}]}""")
    path.endsWith("/leaderboard") -> respond(LEADERBOARD)
    path.endsWith("/votes") -> { votes++; respond("""{"already_voted":false}""") }
    path.endsWith("/release-boosting/invoices") -> respond(
        """{"payment_hash":"h1","bolt11":"${java.io.File("../shared/src/jvmTest/resources/invoices/alby-21sat.txt").readText().trim()}","amount_sats":21,"expires_at":"2026-10-09T22:00:00Z"}""",
        io.ktor.http.HttpStatusCode.Created)
    path.endsWith("/payments/h1") -> respond("""{"payment_hash":"h1","status":"${if (++statusCalls < 2) "pending" else "paid"}","poll_after_seconds":1}""")
    else -> respond("", io.ktor.http.HttpStatusCode.NotFound)
}

/** Koin as the app has it, with a mocked network and an in-memory database. */
class TestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        stopKoin()
        val app = this
        val platform = object : Platform {
            override val app = AppInfo("0.0.1", "Android", "Phone")
            // A file, like on the device.
            override fun sqlDriver(): SqlDriver = AndroidSqliteDriver(HoppDatabase.Schema, app, "hopp.db")
            override fun playbackEngine(): PlaybackEngine = FakeEngine()
            override fun downloadDir(): String = java.io.File(app.cacheDir, "episodes").absolutePath
            override fun downloadScheduler() = space.sovereigncast.hopp.data.DownloadScheduler.None
            override fun secretStore() = space.sovereigncast.hopp.platform.MemorySecretStore()
            override fun crashDir(): String = java.io.File(app.cacheDir, "crash").absolutePath
        }
        val nodesignal = java.io.File("../shared/src/jvmTest/resources/feeds/nodesignal-podhome.xml").readBytes()
        // As slow as a phone on the road: the listener moves on while it loads.
        val http = HttpClient(MockEngine { req ->
            kotlinx.coroutines.delay(1_500)
            when {
                req.url.host == "pm.test" -> scInstance(req.url.encodedPath)
                req.url.encodedPath == "/sc.xml" -> respond(SC_FEED)
                req.url.host == "api.podcastindex.org" ->
                    respond("""{"status":"true","feeds":[{"id":1,"title":"Test Show","url":"https://pod.test/feed.xml","author":"A"},
                        {"id":2,"title":"Nodesignal","url":"https://pod.test/sc.xml","author":"N","podcastGuid":"$SC_GUID"}]}""")
                // The curated list of Sovereigncast shows (ScDirectory).
                req.url.host == "sovereigncast.space" && req.url.encodedPath == "/directory.json" ->
                    respond("""{"version":1,"shows":[{"podcast_guid":"$SC_GUID","title":"Nodesignal","feed_url":"https://pod.test/sc.xml","instance":"https://pm.test"}]}""")
                req.url.encodedPath == "/nodesignal.xml" -> respond(nodesignal)
                req.url.encodedPath == "/2.vtt" -> respond(TRANSCRIPT)
                req.url.host == "nwc.ereignishorizont.xyz" -> if (nwcDown) respond("", io.ktor.http.HttpStatusCode.ServiceUnavailable) else respond("<html>wtf is nwc?</html>")
                req.url.encodedPath == "/1.json" -> respond("""{"version":"1.2.0","chapters":[{"startTime":0,"title":"Intro"},
                    {"startTime":90,"title":"Die Node","img":"https://cdn.test/node.jpg","url":"https://github.com/nodesignal"}]}""")
                else -> respond(FEED)
            }
        })
        startKoin {
            allowOverride(true)
            modules(sharedModule(platform), module {
                single { http }
                // A test key, whatever this build carries.
                single { DirectoryRepository(get(), get(), get(), get(), get(), PodcastIndexCredentials("test-key", "test-secret")) }
            })
        }
        // The first-start tour has its own test; every other one starts past it.
        kotlinx.coroutines.runBlocking {
            org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.data.SettingsRepository>().update { it.copy(onboardingDone = true) }
        }
    }
}

/** The whole app on Android's Compose, lifecycle and main looper
 *  (device feedback 2026-10-08). */
@RunWith(RobolectricTestRunner::class)
// A phone's screen (Pixel-sized) — Robolectric's default 320×470 dp leaves
// now playing's value bar below the edge, where a tap never lands.
@Config(sdk = [35], application = TestApp::class, qualifiers = "en-w411dp-h891dp")
// Native graphics: menus and sheets are popups, and a popup is only shown
// once it has been measured for real.
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class AndroidAppFlowTest {
    @get:Rule val rule = createComposeRule()

    private fun waitFor(text: String) = rule.waitUntil(10_000) { has(text) }
    private val koin get() = org.koin.mp.KoinPlatform.getKoin()
    private fun engine() = koin.get<space.sovereigncast.hopp.platform.PlaybackEngine>()
    /** Waits for app state that no node shows — idling the UI each round, or
     *  the main thread (where the player works) never gets to run. */
    private fun until(check: () -> Boolean) = rule.waitUntil(10_000) { rule.waitForIdle(); check() }
    private fun has(text: String, substring: Boolean = false) = rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    private fun tap(text: String) = rule.onNode(hasText(text) and hasClickAction()).performClick()
    /** The top-most of several — now playing lies over the page it came from. */
    private fun tapTop(text: String) = rule.onAllNodes(hasText(text) and hasClickAction()).onLast().performClick()
    private fun back() = rule.onNode(hasContentDescription("Back")).performClick()

    /** What is on screen, for failure messages. */
    private fun screen(): List<String> = rule.onAllNodes(isRoot()).fetchSemanticsNodes().flatMap { root ->
        fun walk(n: SemanticsNode): List<String> =
            n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } + n.children.flatMap(::walk)
        walk(root)
    }

    /** Discover → feed address → the show page before subscribing → Subscribe → the show's page. */
    private fun subscribe(url: String = "https://pod.test/feed.xml") {
        rule.onNode(hasSetTextAction()).performTextInput(url)
        tap("Open as feed"); waitFor("Subscribe")
        tap("Subscribe"); waitFor("✓ Subscribed")
    }

    @Test
    fun subscribeAndUnsubscribeShowUpEverywhere() {
        rule.setContent { App() }
        tap("Library"); waitFor("No subscriptions yet.")
        tap("Discover"); subscribe()
        tap("Library"); waitFor("Test Show")
        tap("Home"); waitFor("Ep 2")
        check(!has("Ep 1")) { "the back catalogue landed in New: ${screen()}" }
        tap("Library"); waitFor("Test Show")
        tap("Test Show"); waitFor("✓ Subscribed")
        tap("✓ Subscribed"); tap("Unsubscribe")
        waitFor("No subscriptions yet.")
    }

    /** Home's own button, the result opened, back — then Home is Home again,
     *  not the show page with every episode it ever had. */
    @Test
    fun theNewTabStaysTheNewTab() {
        rule.setContent { App() }
        waitFor("Add a show"); tap("Add a show")
        subscribe()
        back()
        tap("Home"); waitFor("Ep 2")
        check(!has("Ep 1") && !has("Search or feed address")) { "New is not New: ${screen()}" }
    }

    /** A show page waiting in another tab's stack closes once the show is gone. */
    @Test
    fun noShowPageOutlivesItsShow() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Library"); waitFor("Test Show")
        tap("Test Show"); waitFor("✓ Subscribed")
        tap("✓ Subscribed"); tap("Unsubscribe")
        waitFor("No subscriptions yet.")
        tap("Discover"); waitFor("Search or feed address")
        rule.waitForIdle()
        check(!has("✓ Subscribed")) { "leftovers: ${screen()}" }
    }

    /** Tapping the tab you are on goes back to its root. */
    @Test
    fun theCurrentTabTakesYouHome() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Library"); waitFor("Test Show")
        tap("Test Show"); waitFor("✓ Subscribed")
        tap("Library"); waitFor("Shows")
        check(!has("✓ Subscribed")) { "still on the show page: ${screen()}" }
    }

    /** The listener does not wait for the result: straight to Library. */
    @Test
    fun aSlowSubscribeStillReachesTheLibrary() {
        rule.setContent { App() }
        tap("Library"); waitFor("No subscriptions yet.")
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/nodesignal.xml")
        tap("Open as feed"); waitFor("Subscribe")
        tap("Subscribe")
        tap("Library")
        waitFor("Nodesignal - Deine Bitcoin-Frequenz")
        tap("Home"); waitFor("Nodesignal-Recap - September 2026")
    }

    // ── package 2 ──────────────────────────────────────────────────────────

    /** The ⋮ of the n-th episode row on screen, top to bottom. */
    private fun menuOf(row: Int) = rule.onAllNodes(hasContentDescription("More") and hasClickAction())[row].performClick()

    @Test
    fun playNextFillsUpNext() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        waitFor("Ep 1")
        menuOf(1); waitFor("Play next"); tap("Play next")
        tap("Library"); waitFor("Up next")
        tap("Up next"); waitFor("Ep 1")
        rule.onNode(hasContentDescription("Remove from up next")).performClick()
        waitFor("Nothing up next")
    }

    @Test
    fun aRequestedDownloadIsListed() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        waitFor("Ep 1")
        menuOf(1); waitFor("Download"); tap("Download")
        kotlinx.coroutines.runBlocking { org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.data.DownloadRepository>().runPending(includeAuto = false) }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Downloaded", substring = true).fetchSemanticsNodes().isNotEmpty() }
        tap("Library"); waitFor("Downloads")
        tap("Downloads"); waitFor("Ep 1")
        check(has("used", substring = true)) { "no space shown: ${screen()}" }
    }

    @Test
    fun theSleepTimerShowsWhatIsSet() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        waitFor("Ep 2"); openPlayer("Ep 2")
        rule.onNode(hasContentDescription("Sleep timer")).performClick()
        waitFor("End of episode"); tap("End of episode")
        waitFor("Episode")
    }

    @Test
    fun skipIntervalsFollowTheSettings() {
        rule.setContent { App() }
        rule.onNode(hasContentDescription("Settings")).performClick()
        tap("Playback")
        waitFor("Skip back")
        rule.onAllNodes(hasText("15 s") and hasClickAction()).onFirst().performClick()
        rule.onNode(hasContentDescription("Back")).performClick()
        tap("Discover"); subscribe()
        waitFor("Ep 2"); openPlayer("Ep 2")
        rule.waitUntil(10_000) { rule.onAllNodes(hasContentDescription("Back 15 seconds, hold for previous chapter")).fetchSemanticsNodes().isNotEmpty() }
    }

    // ── package 3 ──────────────────────────────────────────────────────────

    /** A term goes to the Podcast Index; a result opens its page first. */
    @Test
    fun searchLeadsToTheShowPageBeforeSubscribing() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("test show")
        waitFor("Test Show")
        tap("Test Show")
        // At once, from the search result — the feed takes 1.5 s here.
        waitFor("Subscribe")
        check(has("A") && !has("Ep 2")) { "the directory's data first: ${screen()}" }
        waitFor("Ep 2") // then the real feed
        tap("Subscribe"); waitFor("✓ Subscribed")
        back()
        rule.onNode(hasSetTextAction()).performTextInput("x") // search again: now marked as subscribed
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Subscribed", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    // ── package 4 ──────────────────────────────────────────────────────────

    private fun openEpisode() {
        tap("Discover"); subscribe()
        waitFor("Ep 2"); openPlayer("Ep 2")
    }

    /** A row opens the episode's page; its Play starts it; the mini player opens now playing. */
    private fun openPlayer(title: String) {
        tap(title); waitFor("Show notes")
        tap("Play")
        until { koin.get<space.sovereigncast.hopp.player.PlayerController>().current.value != null }
        org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.player.PlayerController>().expand()
        waitFor("Boost")
    }

    /** No wallet yet: the boost dialog leads to the wallet page. */
    @Test
    fun boostingWithoutAWalletLeadsToTheWallet() {
        rule.setContent { App() }
        openEpisode()
        tapTop("Boost")
        waitFor("Connect a wallet to boost")
        tap("Connect a wallet to boost")
        waitFor("Connection string (nostr+walletconnect://…)")
    }

    /** Streaming cannot start without a wallet — the switch says where to go. */
    @Test
    fun streamingWithoutAWalletLeadsToTheWallet() {
        rule.setContent { App() }
        openEpisode()
        // The streaming switch — the show page's auto-download switch behind it is on.
        rule.onNode(androidx.compose.ui.test.isToggleable() and androidx.compose.ui.test.isOff()).performClick()
        waitFor("Connection string (nostr+walletconnect://…)")
    }

    @Test
    fun aBrokenConnectionStringIsRefusedVisibly() {
        rule.setContent { App() }
        rule.onNode(hasContentDescription("Settings")).performClick()
        tap("Wallet & payments")
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("nostr+walletconnect://nonsense")
        tap("Connect")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Could not connect", substring = true).fetchSemanticsNodes().isNotEmpty() }
        check(!has("Daily budget for boosts and streaming")) { "budget without a wallet: ${screen()}" }
        check(!has("Payment history")) { "the history is a row of the settings, not of the wallet page" }
    }

    /** A wallet's "connect" link opens the wallet page with the string filled in. */
    @Test
    fun aWalletLinkOpensTheWalletPage() {
        rule.setContent { App() }
        waitFor("Add a show")
        IncomingLinks.walletUri.value = "nostr+walletconnect://abc?relay=wss://r.test&secret=x"
        rule.waitUntil(10_000) { rule.onAllNodesWithText("nostr+walletconnect://abc", substring = true).fetchSemanticsNodes().isNotEmpty() }
        check(has("Connect")) { screen().toString() }
    }

    // ── device feedback 2026-10-08 ─────────────────────────────────────────

    private fun rows(): Int = rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.CollectionInfo].rowCount

    /** 325 episodes: the show page loads 20, and 20 more at the end. */
    @Test
    fun aLongShowLoadsItsEpisodesInPages() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/nodesignal.xml")
        tap("Open as feed"); waitFor("Subscribe")
        rule.waitUntil(10_000) { has("325 episodes", substring = true) }
        check(rows() <= 2 + 20) { "the preview shows ${rows()} rows at once" } // header, search field, a page
        tap("Subscribe"); waitFor("✓ Subscribed")
        // The header sits above the list; the list holds the search field and the episodes.
        rule.waitUntil(10_000) { rows() == 1 + 20 }
        check(has("325 episodes")) { "the count is the show's, not the page's" }
        rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToIndex(20)
        rule.waitUntil(10_000) { rows() == 1 + 40 }
    }

    @Test
    fun recentSearchesComeBack() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("test show")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.onNode(hasContentDescription("Cancel")).performClick() // clear the field
        waitFor("Recent searches")
        tap("test show")
        waitFor("Test Show") // searched again
        rule.onNode(hasContentDescription("Cancel")).performClick()
        waitFor("Recent searches")
        tap("Clear")
        rule.waitUntil(10_000) { !has("Recent searches") }
    }

    // ── episode page (device feedback 2026-10-08, as in Pocket Casts) ──────


    /** A tap opens the episode, not the player: show notes with their link
     *  and a time mark that starts the episode right there. */
    @Test
    fun anEpisodeOpensItsPageAndATimeMarkPlaysFromThere() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Ep 2"); waitFor("Show notes")
        check(engine().state.value.itemId == null) { "tapping must not play" }
        check(has("Play")) { screen().toString() }
        // The time mark is a link inside the show notes' text: follow it as a tap would.
        val notes = rule.onNode(hasText("(12:34) Hauptteil", substring = true)).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].first { "12:34" in it.text }
        val mark = notes.getLinkAnnotations(0, notes.length).map { it.item }.filterIsInstance<androidx.compose.ui.text.LinkAnnotation.Clickable>().single()
        rule.runOnUiThread { mark.linkInteractionListener!!.onClick(mark) }
        until { engine().state.value.positionMs == 754_000L }
    }

    /** The round button in the row plays without opening the player. */
    @Test
    fun theRowsPlayButtonPlaysInPlace() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        waitFor("Ep 2")
        rule.onAllNodes(hasContentDescription("Play") and hasClickAction())[0].performClick()
        until { engine().state.value.isPlaying }
        check(!koin.get<space.sovereigncast.hopp.player.PlayerController>().expanded.value) { "the player opened" }
        waitFor("✓ Subscribed") // still on the show page
    }

    /** Device feedback 2026-10-08: the whole show can be boosted from its page. */
    @Test
    fun aShowCanBeBoostedAsAWhole() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Boost")
        waitFor("For the whole show")
        waitFor("Connect a wallet to boost")
    }

    /** Device feedback 2026-10-08: show notes, chapters and people as tabs. */
    @Test
    fun theEpisodePageHasTabs() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Ep 2"); waitFor("Show notes")
        check(has("(12:34) Hauptteil", substring = true) && !has("Karsten")) { screen().toString() }
        tap("People")
        waitFor("Karsten")
        check(!has("(12:34) Hauptteil", substring = true)) { "the notes stay hidden on the People tab" }
    }

    // ── Sovereigncast layer (ADR 0018, phase 1) ────────────────────────────

    /** A verified show: badge, the running round, the community tab with a free vote. */
    @Test
    fun aSovereigncastShowShowsItsLayer() {
        rule.setContent { App() }
        tap("Discover"); subscribe("https://pod.test/sc.xml")
        waitFor("✓ Sovereigncast")
        waitFor("RELEASE BOOSTING")
        check(has("E297 - BTC++ Berlin")) { screen().toString() }
        tap("Community")
        waitFor("BIP110")
        rule.onNode(hasText("▲ 12") and hasClickAction()).performClick()
        waitFor("▲ 13")
        // Voted once, the button stays down (disabled) once the vote is stored.
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("▲ 13") and androidx.compose.ui.test.isNotEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Without a wallet the invoice goes to a wallet app; Hopp waits for the instance. */
    @Test
    fun releaseBoostingIsPaidAndConfirmed() {
        rule.setContent { App() }
        tap("Discover"); subscribe("https://pod.test/sc.xml")
        waitFor("RELEASE BOOSTING")
        tap("Boost")
        waitFor("Bring E297 - BTC++ Berlin out sooner")
        tap("21 · 1 min")
        tap("Pay 21 sats with a wallet app")
        waitFor("Open wallet app")
        rule.waitUntil(15_000) { has("21 sats sent — thank you!") }
    }

    // ── device feedback 2026-10-09 ─────────────────────────────────────────

    /** A horizontal swipe moves between the episode page's tabs. */
    @Test
    fun aSwipeChangesTheEpisodeTab() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Ep 2"); waitFor("Show notes")
        // Show notes → transcript, the next tab of this episode.
        rule.onNode(hasText("(12:34) Hauptteil", substring = true)).performTouchInput { swipeLeft() }
        waitFor("Willkommen zur zweiten Folge.")
        rule.waitUntil(10_000) { !has("(12:34) Hauptteil", substring = true) }
        rule.onNode(hasText("Willkommen zur zweiten Folge.") and hasClickAction()).performTouchInput { swipeRight() }
        rule.waitUntil(10_000) { has("(12:34) Hauptteil", substring = true) }
    }

    /** The episode page boosts the episode itself. */
    @Test
    fun theEpisodePageBoosts() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Ep 2"); waitFor("Show notes")
        tap("Boost")
        waitFor("Ep 2 · at 0:00")
        waitFor("Connect a wallet to boost")
    }

    /** Auto-download and unsubscribe live in the show's menu now. */
    @Test
    fun theShowMenuHoldsItsSettings() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        rule.onNode(hasContentDescription("Show options")).performClick()
        waitFor("Download new episodes on Wi-Fi")
        tap("Unsubscribe")
        waitFor("Unsubscribe from Test Show? Positions of this show are removed.")
    }

    /** The round's card opens what the community page shows; the sheet boosts too. */
    @Test
    fun releaseBoostingShowsItsDetails() {
        rule.setContent { App() }
        tap("Discover"); subscribe("https://pod.test/sc.xml")
        waitFor("E297 - BTC++ Berlin")
        rule.onNode(hasText("E297 - BTC++ Berlin") and hasClickAction()).performClick()
        waitFor("Jan-Paul, Thorsten & Calso waren zu Gast.")
        check(has("1 of 72 blocks mined · 210 sats each")) { screen().toString() }
        check(has("🔒 A clip unlocks at block 36")) { screen().toString() }
        tapTop("Boost")
        waitFor("Bring E297 - BTC++ Berlin out sooner")
    }

    /** A suggestion opens in full, and can be voted on from there. */
    @Test
    fun aSuggestionOpensInFull() {
        rule.setContent { App() }
        tap("Discover"); subscribe("https://pod.test/sc.xml")
        waitFor("✓ Sovereigncast")
        tap("Community")
        waitFor("BIP110")
        rule.onNode(hasText("BIP110") and hasClickAction()).performClick()
        waitFor("by Anonymous · 06.07.2026")
        check(has(SUGGESTION_TEXT)) { screen().toString() }
    }

    /** The community's boosts: this month's top, the latest, the all-time ranking. */
    @Test
    fun theBoostsTabShowsTheLeaderboard() {
        rule.setContent { App() }
        tap("Discover"); subscribe("https://pod.test/sc.xml")
        waitFor("✓ Sovereigncast")
        tap("Boosts")
        waitFor("Top boosts in October 2026")
        check(has("Gemeinsam basteln") && has("4,221 sats") && has("Anonymous")) { screen().toString() }
        // The tab's own list — the pager around it scrolls by index too.
        rule.onAllNodes(androidx.compose.ui.test.hasScrollToIndexAction()).onLast().performScrollToNode(hasText("Blaubär"))
        waitFor("Blaubär")
    }

    /** Device feedback 2026-10-09: opened while the leaderboard still loads, the
     *  tab jumped down to the all-time list. It opens at the month's top. */
    @Test
    fun theBoostsTabOpensAtTheTop() {
        rule.setContent { App() }
        tap("Discover"); subscribe("https://pod.test/sc.xml")
        rule.waitUntil(10_000) { has("Boosts") }
        tap("Boosts") // before the leaderboard has arrived
        waitFor("Top boosts in October 2026")
        rule.waitForIdle()
        rule.onNodeWithText("Top boosts in October 2026").assertIsDisplayed()
        rule.onNodeWithText("Gemeinsam basteln").assertIsDisplayed()
    }

    /** Scrolling the episodes moves the show's header away; back at the top it returns. */
    @Test
    fun theHeaderScrollsAwayWithTheList() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/nodesignal.xml")
        tap("Open as feed"); waitFor("Subscribe")
        tap("Subscribe"); waitFor("✓ Subscribed")
        rule.waitUntil(10_000) { rows() == 1 + 20 }
        rule.onNodeWithText("325 episodes").assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onNodeWithText("325 episodes").assertIsNotDisplayed()
        repeat(4) { rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performTouchInput { swipeDown() } }
        rule.waitForIdle()
        rule.onNodeWithText("325 episodes").assertIsDisplayed()
    }

    // ── package 5 ──────────────────────────────────────────────────────────

    /** The transcript follows the episode; a line jumps there; search finds or says so. */
    @Test
    fun theTranscriptReadsAlongAndJumps() {
        rule.setContent { App() }
        openEpisode()
        tapTop("Transcript") // the player's chip, over the episode page's tab
        waitFor("Willkommen zur zweiten Folge.")
        check(has("Karsten")) { "the speaker" }
        tap("Jetzt kommt der Hauptteil.")
        until { engine().state.value.positionMs == 754_000L }
        rule.onNode(hasSetTextAction()).performTextInput("Bitcoin")
        waitFor("Nothing found")
    }

    @Test
    fun thePlayerShowsWhoIsInTheEpisode() {
        rule.setContent { App() }
        openEpisode()
        tapTop("People") // the player's chip, over the episode page's tab
        waitFor("Host · Cast")
        check(has("Karsten") && has("primal.net ↗")) { screen().toString() }
    }

    /** Who sees what (ADR 0015), behind the settings. */
    @Test
    fun thePrivacyPageSaysWhoSeesWhat() {
        rule.setContent { App() }
        rule.onNode(hasContentDescription("Settings")).performClick()
        rule.onNode(hasText("Privacy") and hasClickAction()).performScrollTo().performClick()
        rule.waitUntil(10_000) { has("Hopp collects nothing itself", substring = true) }
        check(has("Podcast Index")) { screen().toString() }
        check(has("Hopp/0.0.1 (Android; Phone)", substring = true)) { screen().toString() }
    }

    /** podcast:funding on the show page. */
    @Test
    fun theShowsOwnSupportLinkIsThere() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        waitFor("♥ Unterstütze uns ›")
    }

    // ── device feedback 2026-10-09: transcript and clips on the episode page ──

    /** The episode page's transcript, readable before playing; a line starts it there. */
    @Test
    fun theEpisodePageHasTheTranscript() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Ep 2"); waitFor("Show notes")
        tap("Transcript")
        waitFor("Jetzt kommt der Hauptteil.")
        check(has("12:34")) { "times, while not playing along: ${screen()}" }
        tap("Jetzt kommt der Hauptteil.")
        until { engine().state.value.positionMs == 754_000L && engine().state.value.isPlaying }
    }

    /** A clip plays its stretch, then stops and goes back. */
    @Test
    fun theEpisodePagePlaysItsClips() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Ep 2"); waitFor("Show notes")
        tap("Clips")
        waitFor("Der Kern")
        check(has("12:34 · 0:30")) { screen().toString() }
        tap("Der Kern")
        until { engine().state.value.positionMs == 754_000L && engine().state.value.isPlaying }
        rule.runOnUiThread { engine().seekTo(784_000) }
        until { !engine().state.value.isPlaying && engine().state.value.positionMs == 0L }
    }

    /** Everything the feed says about a person; the show's people stand in for an episode without. */
    @Test
    fun peopleShowTheirMetadataAndTheShowsStandIn() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Ep 2"); waitFor("Show notes")
        tap("People")
        waitFor("Host · Cast")
        check(has("primal.net ↗") && !has("Thorsten")) { "the episode's own people only: ${screen()}" }
        back()
        tap("Ep 1"); waitFor("People")
        tap("People") // after Ep 1's chapters
        waitFor("Thorsten")
    }

    // ── device feedback 2026-10-09 (later) ─────────────────────────────────

    /** Home's sections fold away and stay folded. */
    @Test
    fun homeSectionsFold() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Home")
        waitFor("Ep 2")
        rule.onNode(hasText("New") and hasClickAction()).performClick()
        rule.waitUntil(10_000) { !has("Ep 2") }
        until { kotlinx.coroutines.runBlocking { koin.get<space.sovereigncast.hopp.data.SettingsRepository>().current() }.homeCollapsed == setOf("new") }
        rule.onNode(hasText("New") and hasClickAction()).performClick()
        waitFor("Ep 2")
    }

    /** Chapters with their picture and their link (ADR 0012). */
    @Test
    fun chaptersShowTheirLink() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Ep 1"); waitFor("Chapters")
        waitFor("Die Node")
        waitFor("github.com ↗")
    }

    /** A crash last time: the report is offered, read, and deleted — never sent by itself. */
    @Test
    fun aCrashReportIsOfferedToShare() {
        val reports = koin.get<space.sovereigncast.hopp.platform.CrashReports>()
        reports.write("Hopp crash report\nVersion: 0.0.1\n\njava.lang.IllegalStateException: boom")
        rule.setContent { App() }
        waitFor("Crash report")
        tap("Look at it")
        rule.waitUntil(10_000) { has("java.lang.IllegalStateException: boom", substring = true) }
        check(has("Share") && has("As a GitLab issue")) { screen().toString() }
        tap("Delete")
        until { reports.pending() == null }
    }

    /** How often Hopp looks for new episodes is the listener's choice. */
    @Test
    fun theBackgroundRefreshIsSet() {
        rule.setContent { App() }
        rule.onNode(hasContentDescription("Settings")).performClick()
        tap("Downloads & updates")
        tap("Every 3 h")
        until { kotlinx.coroutines.runBlocking { koin.get<space.sovereigncast.hopp.data.SettingsRepository>().current() }.refreshHours == 3 }
        tap("Off")
        rule.waitUntil(10_000) { has("Pulling down on a list always looks right away.", substring = true) }
        rule.waitUntil(10_000) { !has("Only on Wi-Fi") } // no Wi-Fi switch without a refresh
    }

    // ── device feedback 2026-10-10 ─────────────────────────────────────────

    private fun settings() = kotlinx.coroutines.runBlocking { koin.get<space.sovereigncast.hopp.data.SettingsRepository>().current() }
    private fun tourNotDone() = kotlinx.coroutines.runBlocking {
        koin.get<space.sovereigncast.hopp.data.SettingsRepository>().update { it.copy(onboardingDone = false) }
    }

    /** The first start walks through Hopp; its last page connects a wallet. */
    @Test
    fun theFirstStartShowsTheTour() {
        tourNotDone()
        rule.setContent { App() }
        waitFor("Welcome to Hopp")
        repeat(3) { tap("Next") }
        waitFor("Value for value")
        tap("Next")
        waitFor("Connect your wallet")
        tap("Connect a wallet")
        waitFor("Connection string (nostr+walletconnect://…)")
        until { settings().onboardingDone }
    }

    @Test
    fun theTourCanBeSkippedAndShownAgain() {
        tourNotDone()
        rule.setContent { App() }
        waitFor("Welcome to Hopp")
        tap("Skip")
        rule.waitUntil(10_000) { !has("Welcome to Hopp") }
        until { settings().onboardingDone }
        rule.onNode(hasContentDescription("Settings")).performClick()
        tap("About Hopp")
        tap("Show the tour again")
        waitFor("Welcome to Hopp")
    }

    /** The settings as an overview: the wallet and its history are rows of their own. */
    @Test
    fun theSettingsOverviewLeadsToEveryPart() {
        rule.setContent { App() }
        rule.onNode(hasContentDescription("Settings")).performClick()
        waitFor("No wallet connected — needed for boosts")
        tap("Payment history")
        waitFor("No payments yet")
        back()
        tap("Notifications")
        waitFor("After a quick boost from the lock screen")
        check(settings().boostFeedback == "errors") { "only failures by default" }
        tap("Always")
        until { settings().boostFeedback == "always" }
    }

    /** Release Boosting at a subscribed show shows on Home, quietly; a tap opens the show. */
    @Test
    fun homeShowsWhatRunsAtYourShows() {
        rule.setContent { App() }
        tap("Discover"); subscribe("https://pod.test/sc.xml")
        waitFor("✓ Sovereigncast")
        tap("Home")
        waitFor("At your shows")
        waitFor("RELEASE BOOSTING · Nodesignal")
        rule.onNode(hasText("E297 - BTC++ Berlin") and hasClickAction()).performClick()
        waitFor("✓ Sovereigncast")
    }

    // ── device feedback 2026-10-10 (later) ─────────────────────────────────

    /** With a wallet connected, the tour does not ask for one. */
    @Test
    fun theTourSkipsTheWalletPageWhenOneIsConnected() {
        val secrets = koin.get<space.sovereigncast.hopp.platform.SecretStore>()
        secrets.put("nwc_uri", "nostr+walletconnect://" + "a".repeat(64) + "?relay=wss://r.test&secret=" + "b".repeat(64))
        secrets.put("nwc_info", """{"methods":["pay_invoice","pay_keysend"]}""")
        tourNotDone()
        rule.setContent { App() }
        waitFor("Welcome to Hopp")
        repeat(3) { tap("Next") }
        waitFor("Value for value")
        check(!has("Connect a wallet") && !has("Next")) { screen().toString() }
        tap("Let's go")
        until { settings().onboardingDone }
    }

    /** Settings opened from a tab close when the listener goes to another tab. */
    @Test
    fun leavingTheSettingsForATabClosesThem() {
        rule.setContent { App() }
        tap("Library")
        rule.onNode(hasContentDescription("Settings")).performClick()
        waitFor("Value for value")
        tap("Discover")
        rule.onNode(hasSetTextAction()).assertExists()
        tap("Library")
        rule.waitUntil(10_000) { !has("Value for value") }
        rule.onNode(hasContentDescription("Settings")).assertExists() // a tab's top bar, not the settings'
    }

    /** Search marks Sovereigncast shows from the curated list; the badge explains; the filter lists them. */
    @Test
    fun searchMarksAndFiltersSovereigncastShows() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("nodesignal")
        waitFor("Nodesignal")
        rule.onAllNodes(hasText("✓ Sovereigncast") and hasClickAction()).onLast().performClick()
        waitFor("What Sovereigncast brings")
        check(has("More about Sovereigncast")) { screen().toString() }
        rule.onNode(hasText("More about Sovereigncast") and hasClickAction()).assertExists()
    }

    @Test
    fun theSovereigncastFilterListsTheShows() {
        rule.setContent { App() }
        tap("Discover")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("✓ Sovereigncast") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() } // the chip, once the list is in
        rule.onNode(hasText("✓ Sovereigncast") and hasClickAction()).performClick()
        waitFor("Nodesignal")
        check(!has("Test Show")) { screen().toString() }
    }

    // ── device feedback 2026-10-11 ─────────────────────────────────────────

    /** A subscribed show's episodes can be searched by topic, all of them. */
    @Test
    fun aShowsEpisodesCanBeSearched() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/nodesignal.xml")
        tap("Open as feed"); waitFor("Subscribe")
        tap("Subscribe"); waitFor("✓ Subscribed")
        rule.onNode(hasText("Search episodes") and hasSetTextAction()).performTextInput("meshtastic")
        rule.waitUntil(10_000) { has("episodes found", substring = true) }
        rule.waitUntil(10_000) { has("E246 - Selbstsouveräne Kommunikation mit Meshtastic", substring = true) }
    }

    /** Before subscribing too, in the feed already loaded. */
    @Test
    fun thePreviewSearchesItsEpisodes() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/nodesignal.xml")
        tap("Open as feed"); waitFor("Subscribe")
        rule.waitUntil(10_000) { has("325 episodes", substring = true) }
        rule.onNode(hasText("Search episodes") and hasSetTextAction()).performTextInput("xyzzy-nothing")
        waitFor("No episode found")
    }

    /** A favourite is found again on Home; without any there is no such section. */
    @Test
    fun favouritesAreOnHome() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Home"); waitFor("Ep 2")
        check(!has("Favourites")) { "no favourites, no section" }
        tap("Ep 2"); waitFor("Show notes")
        rule.onNode(hasContentDescription("Add to favourites")).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasContentDescription("Remove from favourites")).fetchSemanticsNodes().isNotEmpty() }
        tap("Home")
        waitFor("Favourites")
        tapTop("All 1 ›") // the favourites', below "New"'s
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Ep 2") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() && !has("New") }
    }

    /** The tour links the NWC explainer — only when the page is there. */
    @Test
    fun theTourLinksTheNwcExplainer() {
        tourNotDone()
        rule.setContent { App() }
        waitFor("Welcome to Hopp")
        repeat(4) { tap("Next") }
        waitFor("Connect your wallet")
        waitFor("nwc.ereignishorizont.xyz ↗")
    }

    @Test
    fun aDeadNwcExplainerIsNotLinked() {
        nwcDown = true
        try {
            tourNotDone()
            rule.setContent { App() }
            waitFor("Welcome to Hopp")
            repeat(4) { tap("Next") }
            waitFor("Connect your wallet")
            rule.waitForIdle()
            Thread.sleep(2_000)
            rule.waitForIdle()
            check(!has("nwc.ereignishorizont.xyz ↗") && !has("What NWC is and how to set it up:")) { screen().toString() }
        } finally { nwcDown = false }
    }
}
