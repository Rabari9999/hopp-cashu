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
import androidx.compose.ui.test.performClick
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

private const val FEED = """<?xml version="1.0"?><rss version="2.0"><channel><title>Test Show</title>
<item><title>Ep 2</title><guid>ep-2</guid><pubDate>Tue, 02 Jun 2026 06:00:00 +0000</pubDate><enclosure url="https://cdn.test/2.mp3" type="audio/mpeg"/></item>
<item><title>Ep 1</title><guid>ep-1</guid><pubDate>Mon, 01 Jun 2026 06:00:00 +0000</pubDate><enclosure url="https://cdn.test/1.mp3" type="audio/mpeg"/></item>
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
        }
        val nodesignal = java.io.File("../shared/src/jvmTest/resources/feeds/nodesignal-podhome.xml").readBytes()
        // As slow as a phone on the road: the listener moves on while it loads.
        val http = HttpClient(MockEngine { req ->
            kotlinx.coroutines.delay(1_500)
            when {
                req.url.host == "api.podcastindex.org" ->
                    respond("""{"status":"true","feeds":[{"id":1,"title":"Test Show","url":"https://pod.test/feed.xml","author":"A"}]}""")
                req.url.encodedPath == "/nodesignal.xml" -> respond(nodesignal)
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
    }
}

/** The whole app on Android's Compose, lifecycle and main looper
 *  (device feedback 2026-10-08). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "en")
// Native graphics: menus and sheets are popups, and a popup is only shown
// once it has been measured for real.
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class AndroidAppFlowTest {
    @get:Rule val rule = createComposeRule()

    private fun waitFor(text: String) = rule.waitUntil(10_000) { has(text) }
    private fun has(text: String, substring: Boolean = false) = rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    private fun tap(text: String) = rule.onNode(hasText(text) and hasClickAction()).performClick()
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
        tap("New"); waitFor("Ep 2")
        check(!has("Ep 1")) { "the back catalogue landed in New: ${screen()}" }
        tap("Library"); waitFor("Test Show")
        tap("Test Show"); waitFor("✓ Subscribed")
        tap("✓ Subscribed"); tap("Unsubscribe")
        waitFor("No subscriptions yet.")
    }

    /** New's own button, the result opened, back — then New is New again,
     *  not the show page with every episode it ever had. */
    @Test
    fun theNewTabStaysTheNewTab() {
        rule.setContent { App() }
        waitFor("Add a show"); tap("Add a show")
        subscribe()
        back()
        tap("New"); waitFor("Ep 2")
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
        tap("New"); waitFor("Nodesignal-Recap - September 2026")
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
        waitFor("Ep 2")
        tap("Ep 2")
        rule.waitUntil(10_000) { rule.onAllNodes(hasContentDescription("Sleep timer")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasContentDescription("Sleep timer")).performClick()
        waitFor("End of episode"); tap("End of episode")
        waitFor("Episode")
    }

    @Test
    fun skipIntervalsFollowTheSettings() {
        rule.setContent { App() }
        rule.onNode(hasContentDescription("Settings")).performClick()
        waitFor("Skip back")
        rule.onAllNodes(hasText("15 s") and hasClickAction()).onFirst().performClick()
        rule.onNode(hasContentDescription("Back")).performClick()
        tap("Discover"); subscribe()
        waitFor("Ep 2"); tap("Ep 2")
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
}
