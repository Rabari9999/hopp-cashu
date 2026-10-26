package space.sovereigncast.hopp.ui

import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToNodeAction
import android.app.Application
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.longClick
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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextClearance
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
<podcast:trailer pubdate="Mon, 01 Jun 2026 05:00:00 +0000" url="https://cdn.test/trailer-old.mp3" length="1" type="audio/mpeg">Old trailer</podcast:trailer>
<podcast:trailer pubdate="Mon, 01 Jun 2026 06:00:00 +0000" url="https://cdn.test/trailer.mp3" length="1" type="audio/mpeg">So klingt Test Show</podcast:trailer>
<podcast:person role="host">Thorsten</podcast:person>
<podcast:value type="lightning" method="keysend"><podcast:valueRecipient name="Host" type="node" address="02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" split="100"/></podcast:value>
<item><title>Ep 2</title><guid>ep-2</guid><pubDate>Tue, 02 Jun 2026 06:00:00 +0000</pubDate><enclosure url="https://cdn.test/2.mp3" type="audio/mpeg"/><description><![CDATA[<p>Mehr bei <a href="https://x.test">X</a>.</p><p>(12:34) Hauptteil</p>]]></description><podcast:person role="host" group="cast" href="https://primal.net/p/karsten">Karsten</podcast:person><podcast:transcript url="https://cdn.test/2.vtt" type="text/vtt" language="de"/><podcast:soundbite startTime="754" duration="30">Der Kern</podcast:soundbite></item>
<item><title>Ep 1</title><guid>ep-1</guid><pubDate>Mon, 01 Jun 2026 06:00:00 +0000</pubDate><enclosure url="https://cdn.test/1.mp3" length="48000000" type="audio/mpeg"/><podcast:chapters url="https://cdn.test/1.json" type="application/json+chapters"/></item>
</channel></rss>"""

/** Every episode plays an hour long here. */
private const val HOUR = 3_600_000L

private class FakeEngine : PlaybackEngine {
    private val s = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = s
    override fun load(item: PlaybackItem, playWhenReady: Boolean) { s.value = PlaybackState(item.id, playWhenReady, positionMs = item.startPositionMs, durationMs = HOUR) }
    override fun play() { s.value = s.value.copy(isPlaying = true) }
    override fun pause() { s.value = s.value.copy(isPlaying = false) }
    override fun seekTo(positionMs: Long) { s.value = s.value.copy(positionMs = positionMs) }
    override fun setSpeed(speed: Float) { s.value = s.value.copy(speed = speed) }
    override fun release() = Unit
    // As Android's engine: the switches for both show (ExoPlayer, LoudnessEnhancer).
    override val supportsSkipSilence = true
    override val supportsVolumeBoost = true
}

/** A show in Nodesignal's podcast:podroll (the test feed nodesignal-podhome.xml). */
private const val ROLLED_GUID = "537e4d76-e51c-5a01-a88d-83450d7cf7e8"
private const val SC_GUID = "e8df0b13-47de-544a-99b7-ec7cbd960a16"
private const val SC_FEED = """<?xml version="1.0"?><rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel>
<title>Nodesignal</title><podcast:guid>e8df0b13-47de-544a-99b7-ec7cbd960a16</podcast:guid>
<podcast:txt purpose="sovereigncast">https://pm.test</podcast:txt>
<item><title>E296</title><guid>e296</guid><pubDate>Tue, 02 Jun 2026 06:00:00 +0000</pubDate><enclosure url="https://cdn.test/296.mp3" type="audio/mpeg"/></item>
</channel></rss>"""

private var votes = 12
/** The NWC explainer the tour links to is down (device feedback 2026-10-11). */
private var nwcDown = false
/** What the phone says about VPN and private DNS, settable per test. */
private val testNetwork = MutableStateFlow(space.sovereigncast.hopp.platform.NetworkPrivacy(vpn = false, privateDns = false))
private const val TRANSCRIPT = "WEBVTT\n\n00:00:01.000 --> 00:00:05.000\n<v Karsten>Willkommen zur zweiten Folge.\n\n00:12:34.000 --> 00:12:40.000\n<v Karsten>Jetzt kommt der Hauptteil.\n"

/** A month with ten top boosts and a long all-time list — longer than the screen. */
private val LEADERBOARD = buildString {
    append("""{"month":"2026-10","top_boosts":[""")
    append((1..10).joinToString(",") { r ->
        val (sender, message) = if (r == 1) "" to "Gemeinsam basteln" else "Booster $r" to "Danke für Folge $r, das war wirklich eine ausgesprochen gute Folge!"
        """{"rank":$r,"amount_sats":${4231 - r * 10},"sender":"$sender","message":"$message","episode":{"episode_guid":"e296","title":"E296"}}"""
    })
    append("""],"recent_boosts":[{"amount_sats":21,"sender":"z","message":"","episode":{"episode_guid":"e296","title":"E296"}},{"amount_sats":2100,"sender":"Langschreiber","message":"$LONG_BOOST","episode":{"episode_guid":"e296","title":"E296"}}],"alltime_boosters":[""")
    append((1..30).joinToString(",") { r ->
        val name = if (r == 1) "Blaubär" else "Hörer $r"
        """{"rank":$r,"sender":"$name","trend":"same","prev_rank":$r,"legendary":${r < 4},"medals":{"gold":${30 - r},"silver":1,"bronze":0}}"""
    })
    append("""],"poll_after_seconds":300}""")
}
private const val LONG_BOOST = "Danke für diese Folge! Ihr habt das Thema so erklärt, dass ich es endlich verstanden habe, und am Ende noch die richtigen Fragen gestellt. Weiter so, und grüßt den Gast!"
private const val SUGGESTION_TEXT = "Eine ganze Folge über BIP110: was es will, wer dafür ist und wer nicht."
private var statusCalls = 0
/** What Hopp last posted to the instance, to check what a form sent. */
private var lastScPost: String? = null
/** The Authorization header of that post — NIP-98 with a Nostr identity (ADR 0021). */
internal var lastScAuth: String? = null
/** Whether Hopp posted to the instance since the test reset it. */
internal var scPosted = false

/** Amber as the app sees it, in tests: installed only when a test says so. */
internal object FakeAmber : space.sovereigncast.hopp.nostr.ExternalSigner {
    var installed = false
    var reject = false
    val keys = space.sovereigncast.hopp.nostr.KeyPair.generate()
    override fun available() = installed
    override suspend fun login(permissions: List<space.sovereigncast.hopp.nostr.SignerPermission>): Pair<String, String> {
        if (reject) throw space.sovereigncast.hopp.nostr.SignerRejected()
        return keys.publicKeyHex to "com.greenart7c3.nostrsigner"
    }
    override suspend fun nip44(signerPackage: String, encrypt: Boolean, text: String, peer: String, currentUser: String, interactive: Boolean): String {
        val key = space.sovereigncast.hopp.nostr.Nip44.conversationKey(keys.secretKey, keys.publicKey)
        return if (encrypt) space.sovereigncast.hopp.nostr.Nip44.encrypt(text, key) else space.sovereigncast.hopp.nostr.Nip44.decrypt(text, key)
    }
    override suspend fun signEvent(signerPackage: String, eventJson: String, eventId: String, currentUser: String, interactive: Boolean): space.sovereigncast.hopp.nostr.SignerResult {
        val o = org.json.JSONObject(eventJson)
        fun str(k: String) = o.get(k).toString()
        val arr = o.getJSONArray("tags")
        val tags = (0 until arr.length()).map { i -> arr.getJSONArray(i).let { t -> (0 until t.length()).map(t::getString) } }
        val e = space.sovereigncast.hopp.nostr.Event.sign(keys, str("kind").toInt(), str("content"), tags, str("created_at").toLong())
        return space.sovereigncast.hopp.nostr.SignerResult(null, e.sig)
    }
}

/** Relays that take every event at once, so publishing a profile needs no network. */
internal object OkRelays : space.sovereigncast.hopp.nostr.RelayConnector {
    override suspend fun connect(url: String) = object : space.sovereigncast.hopp.nostr.RelaySession {
        private val channel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        override val incoming: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow { for (m in channel) emit(m) }
        override suspend fun send(message: String) {
            // A query finds nothing here: end of stored events at once.
            Regex("^\\[\"REQ\",\"([^\"]+)\"").find(message)?.let { channel.trySend("""["EOSE","${it.groupValues[1]}"]"""); return }
            val id = Regex("\"id\":\"([0-9a-f]{64})\"").find(message)?.groupValues?.get(1) ?: return
            channel.trySend("""["OK","$id",true,""]""")
        }
        override suspend fun close() { channel.close() }
    }
}
/** Whether the round's halving clip is unlocked, settable per test. */
private var clipUnlocked = false
/** What the round has raised so far, settable per test (its goal is 15 120). */
private var roundRaised = 210L
/** A suggestion submitted in this test run, listed once there. */
private var submitted: String? = null
private val INVOICE_21 get() = """{"payment_hash":"596b662c3cd19554e15b3bdd8553c7af2bedbc13835db3fd7167f9203878b7d9","bolt11":"${java.io.File("../shared/src/jvmTest/resources/invoices/alby-21sat.txt").readText().trim()}","amount_sats":21,"expires_at":"2026-10-09T22:00:00Z"}"""

/** A Sovereigncast instance as the Listener API answers (listener-v1.yaml). */
private fun io.ktor.client.engine.mock.MockRequestHandleScope.scInstance(path: String, post: Boolean = false) = when {
    path == "/api/listener/meta" -> respond("""{"api_versions":["v1"],"instance":{"id":"i","software":"sovereigncast","version":"dev"}}""")
    path == "/api/listener/v1/shows/by-guid/$SC_GUID" -> respond("""{"podcast_guid":"$SC_GUID","title":"Nodesignal","feed_url":"https://pod.test/sc.xml",
        "capabilities":{"release_boosting":true,"boost2unlock":false,"halving_clip":true,"suggestions":true,"leaderboard":true,"top_episodes":true,"payment_metadata":true},
        "amounts":{"suggestion_min_sats":21},"poll_after_seconds":3600}""")
    path.endsWith("/release-boosting") -> respond("""{"round":{"episode":{"episode_guid":"e297","episode_nr":297,"title":"E297 - BTC++ Berlin","description":"Jan-Paul, Thorsten & Calso waren zu Gast.","image_url":null},
        "goal_sats":15120,"raised_sats":$roundRaised,"sats_per_minute":21,"publish_at":"2026-10-09T21:50:00Z","chain":{"block_sats":210,"blocks":72,"mined_blocks":1,"halving_block":36},
        "clip":${if (clipUnlocked) """{"unlocked":true,"at_block":36,"title":"Halving-Clip","duration_seconds":42,"url":"https://pm.test/api/listener/v1/shows/$SC_GUID/release-boosting/clip"}""" else """{"unlocked":false,"at_block":36}"""}},"poll_after_seconds":60}""")
    // A new suggestion: the invoice is its spam filter (phase 2).
    post && path.endsWith("/suggestions") -> { submitted = lastScPost?.let { Regex("\"title\":\"([^\"]*)\"").find(it)?.groupValues?.get(1) }; respond(INVOICE_21, io.ktor.http.HttpStatusCode.Created) }
    post && path.endsWith("/suggestions/32/invoices") -> respond(INVOICE_21, io.ktor.http.HttpStatusCode.Created)
    path.endsWith("/suggestions") -> respond("""{"suggestions":[{"id":32,"title":"BIP110","description":"$SUGGESTION_TEXT","username":"Anonymous","amount_sats":2226,"vote_count":$votes,"created_at":"2026-07-06T11:39:40Z"}""" +
        (submitted?.let { """,{"id":33,"title":"$it","description":"","username":"","amount_sats":21,"vote_count":0,"created_at":"2026-10-14T10:00:00Z"}""" } ?: "") + "]}")
    path.endsWith("/top-episodes") -> respond("""{"episodes":[{"rank":1,"episode_guid":"e296","episode_nr":296,"title":"E296","description":"Die meistgeboostete Folge."},
        {"rank":2,"episode_guid":"e100","episode_nr":100,"title":"E100 - Jubiläum","description":"","website_url":"https://nodesignal.space/e100/"}]}""")
    path.endsWith("/leaderboard") -> respond(LEADERBOARD)
    path.endsWith("/votes") -> { votes++; respond("""{"already_voted":false}""") }
    path.endsWith("/release-boosting/invoices") -> respond(
        """{"payment_hash":"596b662c3cd19554e15b3bdd8553c7af2bedbc13835db3fd7167f9203878b7d9","bolt11":"${java.io.File("../shared/src/jvmTest/resources/invoices/alby-21sat.txt").readText().trim()}","amount_sats":21,"expires_at":"2026-10-09T22:00:00Z"}""",
        io.ktor.http.HttpStatusCode.Created)
    path.endsWith("/payments/596b662c3cd19554e15b3bdd8553c7af2bedbc13835db3fd7167f9203878b7d9") -> respond("""{"payment_hash":"596b662c3cd19554e15b3bdd8553c7af2bedbc13835db3fd7167f9203878b7d9","status":"${if (++statusCalls < 2) "pending" else "paid"}","poll_after_seconds":1}""")
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
            override fun networkPrivacy() = space.sovereigncast.hopp.platform.NetworkPrivacySource { testNetwork }
            override fun externalSigner() = FakeAmber
        }
        testNetwork.value = space.sovereigncast.hopp.platform.NetworkPrivacy(vpn = false, privateDns = false)
        val nodesignal = java.io.File("../shared/src/jvmTest/resources/feeds/nodesignal-podhome.xml").readBytes()
        // As slow as a phone on the road: the listener moves on while it loads.
        val http = HttpClient(MockEngine { req ->
            kotlinx.coroutines.delay(1_500)
            when {
                req.url.host == "pm.test" -> {
                    val post = req.method == io.ktor.http.HttpMethod.Post
                    if (post) { lastScPost = (req.body as? io.ktor.http.content.TextContent)?.text; lastScAuth = req.headers["Authorization"]; scPosted = true }
                    scInstance(req.url.encodedPath, post)
                }
                req.url.encodedPath == "/sc.xml" -> respond(SC_FEED)
                // A show whose title sorts before Nodesignal's, with no round of its own.
                req.url.encodedPath == "/alpha.xml" -> respond(FEED.replace("<title>Test Show</title>", "<title>Alpha Show</title>"))
                // A show without value for value: no podcast:value anywhere.
                req.url.encodedPath == "/novalue.xml" -> respond(FEED.replace("<title>Test Show</title>", "<title>Plain Show</title>").replace(Regex("<podcast:value.*?</podcast:value>", RegexOption.DOT_MATCHES_ALL), ""))
                // One of Nodesignal's podroll, as the Podcast Index resolves it; the others it does not know.
                req.url.host == "api.podcastindex.org" && req.url.encodedPath.endsWith("podcasts/byguid") ->
                    if (req.url.parameters["guid"] == ROLLED_GUID)
                        respond("""{"status":"true","feed":{"id":3,"title":"Münzweg 21","url":"https://feed.podbean.com/muenzweg21/feed.xml","author":"M","podcastGuid":"$ROLLED_GUID"}}""")
                    else respond("""{"status":"true","feed":[]}""")
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
                single { space.sovereigncast.hopp.nostr.RelayPublisher(OkRelays) }
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

    private fun waitFor(text: String, substring: Boolean = false) = rule.waitUntil(10_000) { has(text, substring) }
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
    /** The system's back — gesture or key —, not the app bar's arrow. */
    private fun systemBack() = rule.runOnUiThread {
        ((rule as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>).activity as androidx.activity.ComponentActivity).onBackPressedDispatcher.onBackPressed()
    }

    /** The back catalogue never lands in New (device feedback 2026-10-12) — nor, since
     *  "From your shows" left (2026-10-13), anywhere on Home. */
    private fun backCatalogueOffHome(title: String): Boolean = title !in screen()

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
        check(backCatalogueOffHome("Ep 1")) { "the back catalogue landed in New: ${screen()}" }
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
        check(backCatalogueOffHome("Ep 1") && !has("Search or feed address")) { "New is not New: ${screen()}" }
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
        // The cover's badge says so too; the episode not downloaded has none.
        check(rule.onAllNodes(hasContentDescription("Downloaded"), useUnmergedTree = true).fetchSemanticsNodes().size == 1) { "one badge expected: ${screen()}" }
        tap("Library"); waitFor("Downloads")
        tap("Downloads"); waitFor("Ep 1")
        check(has("used", substring = true)) { "no space shown: ${screen()}" }
    }

    /** Device feedback 2026-10-15: an automatic download waiting for Wi-Fi
     *  goes now on request, over mobile data too — no cancel and request again. */
    @Test
    fun aWaitingDownloadGoesNowOnRequest() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        waitFor("Download waiting", substring = true)
        val downloads = org.koin.mp.KoinPlatform.getKoin().get<space.sovereigncast.hopp.data.DownloadRepository>()
        // On mobile data the worker leaves automatic downloads alone.
        kotlinx.coroutines.runBlocking { downloads.runPending(includeAuto = false) }
        check(has("Download waiting", substring = true)) { screen().toString() }
        menuOf(0); waitFor("Download now, mobile data too"); tap("Download now, mobile data too")
        rule.waitUntil(10_000) { kotlinx.coroutines.runBlocking { downloads.waitingCount.first() } == 0L }
        kotlinx.coroutines.runBlocking { downloads.runPending(includeAuto = false) }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Downloaded", substring = true).fetchSemanticsNodes().isNotEmpty() }
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

    /** A show recommended by a subscribed one leaves the recommendations once it is
     *  subscribed too (device feedback 2026-10-13: it stayed, the list was loaded once). */
    @Test
    fun aSubscribedRecommendationLeavesTheList() {
        rule.setContent { App() }
        tap("Discover"); subscribe("https://pod.test/nodesignal.xml")
        back()
        // The recommendations show while nothing is typed.
        rule.onNode(hasSetTextAction()).performTextClearance()
        // Below the fold on a phone: scroll to it once it has been resolved. The page, not one of
        // its carousels (device feedback 2026-10-19): the one list that holds others.
        val discover = rule.onNode(hasScrollToNodeAction() and hasAnyDescendant(hasScrollToNodeAction()))
        rule.waitUntil(10_000) { runCatching { discover.performScrollToNode(hasText("Münzweg 21")) }.isSuccess }
        tap("Münzweg 21"); waitFor("Subscribe")
        tap("Subscribe"); waitFor("✓ Subscribed")
        back()
        // Back on Discover: the show's page is gone. Not a heading — with the category tiles and
        // the carousels above, which one is composed depends on where the list was scrolled to.
        rule.waitUntil(10_000) { !has("✓ Subscribed") && rule.onAllNodes(hasScrollToNodeAction() and hasAnyDescendant(hasScrollToNodeAction())).fetchSemanticsNodes().size == 1 }
        // Gone from the list — not merely scrolled out of view.
        rule.waitUntil(10_000) { runCatching { discover.performScrollToNode(hasText("Münzweg 21")) }.isFailure }
    }

    /** Device feedback 2026-10-19: Discover without a term — what is popular, and every category. */
    @Test
    fun discoverShowsWhatIsPopularAndOpensACategory() {
        rule.setContent { App() }
        tap("Discover")
        waitFor("Popular right now")
        waitFor("Test Show")
        // The categories come first, as tiles; a tap filters what is popular (variant A, 2026-10-19).
        rule.onAllNodes(hasText("Education") and hasClickAction()).onFirst().performClick()
        waitFor("Education · today")
        // And every one from the sheet, where it is chosen too.
        tap("All ›")
        waitFor("Your shows’ categories first", substring = true)
        rule.onAllNodes(hasText("Technology") and hasClickAction()).onLast().performClick()
        waitFor("Technology · today")
        rule.onNode(hasText("Technology") and androidx.compose.ui.test.isSelected()).assertExists()
    }

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

    /** Device feedback 2026-10-15: subscribed from the result list, staying on the search. */
    @Test
    fun aResultIsSubscribedFromTheList() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("test show")
        waitFor("Test Show")
        rule.onAllNodes(hasContentDescription("Subscribe") and hasClickAction()).onFirst().performClick()
        rule.waitUntil(15_000) { rule.onAllNodes(hasContentDescription("Subscribed"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        check(has("Test Show") && rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()) { "still on the search: ${screen()}" }
        check(!has("✓ Subscribed")) { "not on the show page: ${screen()}" }
        tap("Library"); waitFor("Test Show")
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

    /** Sharing (device feedback 2026-10-12): the text and link shown first;
     *  "from here" a switch, off unless turned on. */
    @Test
    fun anEpisodeIsSharedWithItsLinkAndFromHereOnRequest() {
        val shared = mutableListOf<String>()
        val actions = object : PlatformActions {
            override fun pickOpml(onText: (String) -> Unit) = Unit
            override fun exportOpml(text: String) = Unit
            override fun shareText(text: String, subject: String) { shared += text }
        }
        rule.setContent { App(actions) }
        tap("Discover"); subscribe()
        waitFor("Ep 2")
        kotlinx.coroutines.runBlocking {
            val ep = koin.get<space.sovereigncast.hopp.data.EpisodeRepository>().ofPodcast(1).first().first { it.title == "Ep 2" }
            koin.get<space.sovereigncast.hopp.data.EpisodeRepository>().savePosition(ep.id, 754_000)
        }
        tap("Ep 2"); waitFor("Show notes")
        rule.onNode(hasContentDescription("Share") and hasClickAction()).performClick()
        waitFor("From 12:34")
        tap("Share…")
        until { shared.size == 1 }
        val feed = java.net.URLEncoder.encode("https://pod.test/feed.xml", "UTF-8")
        check(shared[0] == "“Ep 2” — Test Show\nhttps://www.sovereigncast.space/open?feed=$feed&episode=ep-2") { shared[0] }

        rule.onNode(hasContentDescription("Share") and hasClickAction()).performClick()
        waitFor("From 12:34")
        rule.onAllNodes(androidx.compose.ui.test.isToggleable()).onLast().performClick()
        waitFor("“Ep 2” from 12:34 — Test Show", substring = true)
        tap("Share…")
        until { shared.size == 2 }
        check(shared[1].endsWith("&episode=ep-2&t=754") && shared[1].startsWith("“Ep 2” from 12:34 — Test Show")) { shared[1] }
    }

    /** Copying (device feedback 2026-10-12): the share sheet's text, and a show's feed address. */
    @Test
    fun textAndTheFeedAddressCanBeCopied() {
        val copied = mutableListOf<String>()
        val actions = object : PlatformActions {
            override fun pickOpml(onText: (String) -> Unit) = Unit
            override fun exportOpml(text: String) = Unit
            override fun copyText(text: String, label: String) { copied += text }
        }
        rule.setContent { App(actions) }
        tap("Discover"); subscribe()
        waitFor("Ep 2"); tap("Ep 2"); waitFor("Show notes")
        rule.onNode(hasContentDescription("Share") and hasClickAction()).performClick()
        waitFor("Copy"); tap("Copy")
        until { copied.size == 1 }
        check(copied[0].startsWith("“Ep 2” — Test Show") && copied[0].contains("episode=ep-2")) { copied[0] }
        back(); waitFor("✓ Subscribed")
        rule.onNode(hasContentDescription("Show options")).performClick()
        tap("Copy feed address")
        until { copied.size == 2 }
        check(copied[1] == "https://pod.test/feed.xml") { copied[1] }
    }

    /** A chapter shared by a long press: from its start, always. */
    @Test
    fun aChapterIsSharedByALongPress() {
        val shared = mutableListOf<String>()
        val actions = object : PlatformActions {
            override fun pickOpml(onText: (String) -> Unit) = Unit
            override fun exportOpml(text: String) = Unit
            override fun shareText(text: String, subject: String) { shared += text }
        }
        rule.setContent { App(actions) }
        tap("Discover"); subscribe()
        waitFor("Ep 1"); tap("Ep 1")
        waitFor("Long-press to share")
        rule.onAllNodes(hasText("Die Node", substring = true) and hasClickAction()).onFirst().performTouchInput { longClick() }
        waitFor("Share…")
        tap("Share…")
        until { shared.size == 1 }
        check(shared[0].startsWith("Chapter “Die Node” of “Ep 1” — Test Show") && shared[0].endsWith("&episode=ep-1&t=90")) { shared[0] }
    }

    /** No wallet yet: the boost dialog leads to the wallet page. */
    @Test
    fun boostingWithoutAWalletLeadsToTheWallet() {
        rule.setContent { App() }
        openEpisode()
        tapTop("Boost")
        waitFor("Connect a wallet to boost")
        // Why a connection and not any wallet app, and where NWC is explained (device feedback 2026-10-12).
        check(has("a wallet app only ever gets one invoice", substring = true)) { screen().toString() }
        waitFor("nwc.ereignishorizont.xyz ↗")
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

    /** Streaming says what it sent and what is set aside; a tap says when
     *  and why, per recipient (device feedback 2026-10-11). */
    @Test
    fun streamingShowsWhatIsSetAside() {
        val secrets = koin.get<space.sovereigncast.hopp.platform.SecretStore>()
        secrets.put("nwc_uri", "nostr+walletconnect://" + "a".repeat(64) + "?relay=wss://r.test&secret=" + "b".repeat(64))
        secrets.put("nwc_info", """{"methods":["pay_invoice","pay_keysend"]}""")
        kotlinx.coroutines.runBlocking { koin.get<space.sovereigncast.hopp.data.SettingsRepository>().update { it.copy(streaming = true, streamRateSats = 10) } }
        rule.setContent { App() }
        openEpisode()
        val item = koin.get<space.sovereigncast.hopp.player.PlayerController>().current.value!!.item
        // A minute and a half heard at 10 sats a minute — and a bit of Ep 1 from before.
        val ep1 = kotlinx.coroutines.runBlocking { koin.get<space.sovereigncast.hopp.data.EpisodeRepository>().ofPodcast(item.podcastId).first().first { it.title == "Ep 1" } }
        kotlinx.coroutines.runBlocking {
            koin.get<space.sovereigncast.hopp.value.ValueService>().accrue(item, 90_000, 90_000)
            koin.get<space.sovereigncast.hopp.value.ValueService>().accrue(ep1, 3_000, 3_000)
        }
        waitFor("0 sats today · 15 set aside ›")
        tap("0 sats today · 15 set aside ›")
        waitFor("Set aside in this episode")
        // All of it is this episode's, and all of it the host's.
        waitFor("Host")
        check(has("Set aside, not sent yet") && has("Send the whole sats now") && has("Next payment")) { screen().toString() }
        // Ep 1's half sat: which episode, and why it waits; a tap opens it.
        waitFor("From other episodes")
        check(has("Ep 1") && has("Test Show · goes out with the next round")) { screen().toString() }
        check(has("How Hopp pays", substring = true)) { screen().toString() }
        rule.onNode(hasText("Ep 1") and hasClickAction()).performClick()
        // Now playing steps aside for Ep 1's page.
        until { !koin.get<space.sovereigncast.hopp.player.PlayerController>().expanded.value }
        waitFor("01.06.2026", substring = true) // Ep 1's page: its date (and size), its own Play
        check(has("Ep 1") && has("Play")) { screen().toString() }
    }

    /** Device feedback 2026-10-26: with coinos, which cannot keysend, nothing said that the host's
     *  share never could go — it piled up as set aside. Now the bar, the sheet and the wallet page
     *  say what this wallet reaches; and that a wallet gives no answer at all. */
    @Test
    fun aWalletWithoutKeysendSaysWhatItCannotReach() {
        val secrets = koin.get<space.sovereigncast.hopp.platform.SecretStore>()
        secrets.put("nwc_uri", "nostr+walletconnect://" + "a".repeat(64) + "?relay=wss://r.test&secret=" + "b".repeat(64))
        secrets.put("nwc_info", """{"methods":["pay_invoice","get_balance"],"relay":"wss://r.test"}""")
        kotlinx.coroutines.runBlocking { koin.get<space.sovereigncast.hopp.data.SettingsRepository>().update { it.copy(streaming = true, streamRateSats = 10) } }
        rule.setContent { App() }
        openEpisode()
        val item = koin.get<space.sovereigncast.hopp.player.PlayerController>().current.value!!.item
        kotlinx.coroutines.runBlocking { koin.get<space.sovereigncast.hopp.value.ValueService>().accrue(item, 90_000, 90_000) }
        waitFor("not possible here ›")
        snapshot("reach-bar")
        tap("not possible here ›")
        waitFor("This episode receives by keysend only, and r.test cannot do that.")
        waitFor("100% · node address – not reachable with r.test, not set aside")
        check(has("0 sats") && !has("Send the whole sats now")) { screen().toString() }
        snapshot("reach-sheet")
        tap("What my wallet can do")
        waitFor("What this connection can do")
        check(has("Recipients with a node address (keysend)") && has("Boosts and streaming to Lightning addresses")) { screen().toString() }
        // The relay of this connection is nowhere: no answer, said as such.
        waitFor("The wallet is not answering right now", substring = true)
        snapshot("reach-wallet")
    }

    /** Device feedback 2026-10-26: a round to coinos read "0 sats" — two shares unknown, one
     *  skipped. The history shows what was to go out, and what became of it. */
    @Test
    fun theHistorySaysWhatBecameOfAPayment() {
        val lines = """[{"name":"Der Jack","address":"jack@x.test","msat":1000,"status":"unknown","route":"lnurl","reason":"OUTCOME_UNKNOWN: no answer from the wallet within 45s"},""" +
            """{"name":"Calso","address":"calso@x.test","msat":1000,"status":"unknown","route":"lnurl","reason":"OUTCOME_UNKNOWN: no answer from the wallet within 45s"},""" +
            """{"name":"Nodesignal","address":"02aa","msat":2000,"status":"skipped","reason":"wallet cannot send keysend"}]"""
        koin.get<space.sovereigncast.hopp.db.HoppDatabase>().paymentQueries.insert(1_780_000_000, "stream", null, null, "Nodesignal", "E298", 0, null, 0, 2_000, lines)
        rule.setContent { App() }
        rule.onNode(hasContentDescription("Settings")).performClick()
        tap("Payment history")
        waitFor("4 sats")
        check(has("2 outcome unknown") && has("2 skipped") && !has("0 sats")) { screen().toString() }
        tap("4 sats")
        waitFor("Unknown means the wallet did not say whether it paid.", substring = true)
        snapshot("history-outcome")
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

    private fun playedTitles(): Set<String> = kotlinx.coroutines.runBlocking {
        val repo = koin.get<space.sovereigncast.hopp.data.EpisodeRepository>()
        val show = koin.get<space.sovereigncast.hopp.data.PodcastRepository>().shows.first().first()
        repo.ofPodcast(show.id).first().filter { it.played }.map { it.title }.toSet()
    }

    /** The show's menu marks all of it heard, after asking (device feedback 2026-10-14). */
    @Test
    fun aWholeShowIsMarkedPlayed() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        rule.onNode(hasContentDescription("Show options")).performClick()
        tap("Mark all as played")
        waitFor("Mark every episode of Test Show as played?")
        rule.onAllNodes(hasText("Mark as played") and hasClickAction()).onLast().performClick()
        until { playedTitles() == setOf("Ep 1", "Ep 2") }
    }

    /** An episode's menu marks it and the older ones heard, as Castamatic does. */
    @Test
    fun anEpisodeAndTheOlderOnesAreMarkedPlayed() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        menuOf(1) // Ep 1, the older of the two
        tap("Mark this and older as played")
        waitFor("Mark “Ep 1” and every older episode of the show as played?")
        rule.onAllNodes(hasText("Mark as played") and hasClickAction()).onLast().performClick()
        until { playedTitles() == setOf("Ep 1") }
    }

    /** A word begun is enough (device feedback 2026-10-14: "Nodesig" found nothing —
     *  the directories match whole words): what Hopp knows is suggested at once. */
    @Test
    fun aWordBegunSuggestsAShow() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("Nodesig")
        waitFor("Suggestions")
        waitFor("Nodesignal")
        // The directories' own answer below, under its heading.
        waitFor("In the directories")
    }

    /** A wallet's QR code instead of copying (device feedback 2026-10-14): what the
     *  camera read connects at once — here a broken string, refused as if pasted. */
    @Test
    fun aScannedConnectionConnectsAtOnce() {
        var scans = 0
        val camera = object : PlatformActions by NoPlatformActions {
            override val canScanQr = true
            override fun scanQr(onText: (String) -> Unit) { scans++; onText(" nostr+walletconnect://nonsense ") }
        }
        rule.setContent { App(camera) }
        rule.onNode(hasContentDescription("Settings")).performClick()
        tap("Wallet & payments")
        tap("Scan QR code")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Could not connect", substring = true).fetchSemanticsNodes().isNotEmpty() }
        check(scans == 1)
        check(has("QR code read", substring = true)) { screen().toString() }
    }

    /** No camera, no button. */
    @Test
    fun withoutACameraThereIsNoScanButton() {
        rule.setContent { App() }
        rule.onNode(hasContentDescription("Settings")).performClick()
        tap("Wallet & payments")
        waitFor("Connect")
        check(!has("Scan QR code")) { screen().toString() }
    }

    /** A wallet's "connect" link opens the wallet page with the string filled in. */
    @Test
    fun aWalletLinkOpensTheWalletPage() {
        rule.setContent { App() }
        waitFor("Add a show")
        IncomingLinks.walletUri.value = "nostr+walletconnect://abc?relay=wss://r.test&secret=x"
        // Masked until asked (security review 2026-10-10, M-01).
        waitFor("Show")
        val masked = androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password)
        rule.onNode(hasSetTextAction() and masked).assertExists()
        tap("Show")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("nostr+walletconnect://abc", substring = true).fetchSemanticsNodes().isNotEmpty() }
        waitFor("Hide")
        check(has("Connect")) { screen().toString() }
    }

    /** Security review 2026-10-10, M-01: the connection string is in no saved
     *  state — the wallet page, saved and restored, starts empty. */
    @Test
    fun theConnectionStringIsNotKeptInSavedState() {
        val restore = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
        IncomingLinks.handOverWallet("nostr+walletconnect://abc?relay=wss://r.test&secret=x")
        restore.setContent { space.sovereigncast.hopp.ui.value.WalletScreen() }
        waitFor("Show")
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        check(!has("Show")) { "the string came back from saved state: ${screen()}" }
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

    /** User request 2026-10-18: an episode not downloaded says how large it is,
     *  in the show's list and on its own page — download, stream or wait for Wi-Fi. */
    @Test
    fun anEpisodeNotDownloadedShowsItsSize() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Library"); tap("Test Show")
        waitFor("45 MB", substring = true)
        tap("Ep 1"); waitFor("Chapters") // the episode page's tab (Ep 1 has chapters, no notes)
        check(!has("Episodes")) { "still on the show page: ${screen()}" }
        check(has("01.06.2026 · 45 MB")) { screen().toString() }
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

    // ── phase 2: the full Sovereigncast layer ──────────────────────────────

    /** A new topic, paid as its spam filter from the show's minimum on, then on the list. */
    @Test
    fun aTopicIsSuggestedAndPaid() {
        submitted = null
        rule.setContent { App() }
        tap("Discover"); subscribe("https://pod.test/sc.xml")
        waitFor("✓ Sovereigncast")
        tap("Community")
        waitFor("+ Suggest a topic")
        tap("+ Suggest a topic")
        waitFor("The payment keeps spam out; once it is paid, your suggestion is public right away. From 21 sats.")
        // Without a topic there is nothing to pay.
        check(rule.onAllNodes(hasText("Pay 21 sats with a wallet app") and hasClickAction() and androidx.compose.ui.test.isEnabled()).fetchSemanticsNodes().isEmpty()) { screen().toString() }
        rule.onNode(hasSetTextAction() and hasText("Topic")).performTextInput("Lightning im Einzelhandel")
        tap("Pay 21 sats with a wallet app")
        rule.waitUntil(15_000) { has("Suggested — thank you! It is on the list now.") }
        val sent = lastScPost.orEmpty()
        check("\"title\":\"Lightning im Einzelhandel\"" in sent && "\"amount_sats\":21" in sent) { sent }
        tap("Close")
        waitFor("Lightning im Einzelhandel")
    }

    /** An open suggestion gets weight with sats, from its sheet. */
    @Test
    fun aSuggestionIsBoosted() {
        rule.setContent { App() }
        tap("Discover"); subscribe("https://pod.test/sc.xml")
        waitFor("✓ Sovereigncast")
        tap("Community")
        waitFor("BIP110")
        rule.onNode(hasText("BIP110") and hasClickAction()).performClick()
        waitFor("by Anonymous · 06.07.2026")
        tapTop("Boost")
        waitFor("Boost “BIP110”")
        tap("…")
        rule.onNode(hasSetTextAction() and hasText("Amount in sats")).performTextReplacement("21")
        tap("Pay 21 sats with a wallet app")
        rule.waitUntil(15_000) { has("21 sats sent — thank you!") }
        check(lastScPost.orEmpty().contains("\"amount_sats\":21")) { lastScPost.toString() }
    }

    /** Device feedback 2026-10-15: a suggestion is boosted from its row, without opening it. */
    @Test
    fun aSuggestionIsBoostedFromItsRow() {
        rule.setContent { App() }
        tap("Discover"); subscribe("https://pod.test/sc.xml")
        waitFor("✓ Sovereigncast")
        tap("Community")
        waitFor("BIP110")
        rule.onAllNodes(hasContentDescription("Boost") and hasClickAction()).onFirst().performClick()
        waitFor("Boost “BIP110”")
        check(!has("by Anonymous · 06.07.2026")) { "no detour through the suggestion: ${screen()}" }
    }

    /** An unlocked clip plays in Hopp's own player, not the browser; closed, it is gone. */
    @Test
    fun anUnlockedClipPlaysInHopp() {
        clipUnlocked = true
        try {
            rule.setContent { App() }
            tap("Discover"); subscribe("https://pod.test/sc.xml")
            waitFor("E297 - BTC++ Berlin")
            rule.onNode(hasText("E297 - BTC++ Berlin") and hasClickAction()).performClick()
            waitFor("Listen to the clip: Halving-Clip · 0:42")
            tap("Listen to the clip: Halving-Clip · 0:42")
            until { engine().state.value.itemId == space.sovereigncast.hopp.player.PlayerController.REMOTE_CLIP_ID }
            // The sheet stays open; the mini player below shows the clip.
            waitFor("Clip · Halving-Clip")
            rule.onNode(hasContentDescription("Close the clip")).performClick()
            rule.waitUntil(10_000) { !has("Clip · Halving-Clip") }
        } finally { clipUnlocked = false }
    }

    /** The show's trailer (podcast:trailer, phase 2) plays from its page — before
     *  subscribing as after — the newest one, as a clip: closed, it is gone. */
    @Test
    fun theTrailerPlaysFromTheShowPage() {
        rule.setContent { App() }
        tap("Discover")
        rule.onNode(hasSetTextAction()).performTextInput("https://pod.test/feed.xml")
        tap("Open as feed"); waitFor("Play the trailer")
        tap("Play the trailer")
        until { engine().state.value.itemId == space.sovereigncast.hopp.player.PlayerController.REMOTE_CLIP_ID }
        waitFor("Trailer · So klingt Test Show")
        rule.onNode(hasContentDescription("Close the clip")).performClick()
        rule.waitUntil(10_000) { !has("Trailer · So klingt Test Show") }
        tap("Subscribe"); waitFor("✓ Subscribed")
        waitFor("Play the trailer")
    }

    /** The show's most popular episodes have a tab of their own, all of them; one
     *  the feed has opens in Hopp (device feedback 2026-10-14). */
    @Test
    fun theTopEpisodesOpenInHopp() {
        rule.setContent { App() }
        tap("Discover"); subscribe("https://pod.test/sc.xml")
        waitFor("✓ Sovereigncast")
        tap("Top episodes")
        waitFor("Die meistgeboostete Folge.")
        check(has("E100 - Jubiläum")) { screen().toString() }
        // The top row itself — the episode list has an "E296" too.
        rule.onNode(hasText("Die meistgeboostete Folge.") and hasClickAction()).performClick()
        // E296's own page (no show notes in this feed), not the show any more.
        rule.waitUntil(10_000) { has("02.06.2026", substring = true) && !has("Die meistgeboostete Folge.") }
    }

    /** The show notes from the player, without closing it (device feedback 2026-10-16). */
    @Test
    fun theShowNotesOpenFromThePlayer() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Ep 2"); waitFor("Play")
        tap("Play")
        until { koin.get<space.sovereigncast.hopp.player.PlayerController>().current.value != null }
        koin.get<space.sovereigncast.hopp.player.PlayerController>().expand()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Show notes") and hasClickAction() and androidx.compose.ui.test.isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("Show notes") and hasClickAction()).onLast().performClick()
        waitFor("(12:34) Hauptteil", substring = true)
        check(koin.get<space.sovereigncast.hopp.player.PlayerController>().expanded.value) { "the player closed" }
    }

    /** Device feedback 2026-10-16: only chips with something behind them — no
     *  greyed-out Community for a show without Sovereigncast; Comments always,
     *  and opening them asks the relays (ADR 0021, stage 2). */
    @Test
    fun thePlayerShowsOnlyChipsWithContentAndTheComments() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Ep 2"); waitFor("Play")
        tap("Play")
        until { koin.get<space.sovereigncast.hopp.player.PlayerController>().current.value != null }
        koin.get<space.sovereigncast.hopp.player.PlayerController>().expand()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Show notes") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        check(!has("Community")) { "a placeholder chip for a show without Sovereigncast" }
        rule.onAllNodes(hasText("Comments") and hasClickAction()).onLast().performClick()
        // Without a VPN the player asks first too (device feedback 2026-10-16).
        waitFor("Load comments")
        check(!has("No comments on this episode yet.")) { "the player loaded without asking" }
        rule.onAllNodes(hasText("Load comments") and hasClickAction()).onLast().performClick()
        waitFor("No comments on this episode yet.")
    }

    /** On the episode page the comments wait for a tap: relays see the IP address and the episode. */
    @Test
    fun theEpisodePageLoadsCommentsOnlyOnRequest() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Ep 2"); waitFor("Play")
        tap("Comments")
        waitFor("Load comments")
        check(!has("No comments on this episode yet.")) { "loaded before asked" }
        // Without a VPN the way to set one up; with one, the green check — and the
        // comments load at once: the relays do not see the address (device feedback 2026-10-16).
        waitFor("No VPN active · set one up")
        testNetwork.value = space.sovereigncast.hopp.platform.NetworkPrivacy(vpn = true, privateDns = false)
        waitFor("VPN active — the relays do not see your IP address")
        waitFor("No comments on this episode yet.")
    }

    /** Device feedback 2026-10-16: a show without value for value has no boost or
     *  streaming to offer — a greyed bar says why. */
    @Test
    fun thePlayerOffersNoBoostWithoutValue() {
        rule.setContent { App() }
        tap("Discover"); subscribe("https://pod.test/novalue.xml")
        tap("Ep 2"); waitFor("Play")
        tap("Play")
        until { koin.get<space.sovereigncast.hopp.player.PlayerController>().current.value != null }
        koin.get<space.sovereigncast.hopp.player.PlayerController>().expand()
        waitFor("This show takes no sats — its feed names no value recipients.")
        check(rule.onAllNodes(hasText("Boost") and hasClickAction()).fetchSemanticsNodes().isEmpty()) { "a boost button that cannot work" }
    }

    /** Writing a comment (ADR 0021, stage 3): without an identity the way to one;
     *  with one, once the note that it stays public, then the comment — and,
     *  with a key from Hopp not backed up, the reminder. */
    @Test
    fun aCommentIsWrittenAfterThePublicNote() {
        val copied = mutableListOf<String>()
        val actions = object : PlatformActions {
            override fun pickOpml(onText: (String) -> Unit) = Unit
            override fun exportOpml(text: String) = Unit
            override fun copyText(text: String, label: String) { copied += text }
            override fun openNostr(entity: String) { copied += "open:$entity" }
        }
        rule.setContent { App(actions) }
        tap("Discover"); subscribe()
        tap("Ep 2"); waitFor("Play")
        tap("Comments")
        waitFor("Load comments")
        tap("Load comments")
        waitFor("Set up a Nostr identity to comment")
        kotlinx.coroutines.runBlocking { koin.get<space.sovereigncast.hopp.identity.IdentityRepository>().create("Me") }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Write a comment…")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction()).onLast().performTextInput("Great episode")
        rule.onNodeWithContentDescription("Send").performClick()
        waitFor("Public, for good")
        tap("Understood")
        waitFor("Back up your key")
        tap("Later")
        waitFor("Great episode")
        check(has("Me")) { "the comment shows under the listener's name" }
        // Beside like and reply a zap (device feedback 2026-10-19) — none on one's own comment.
        rule.onNodeWithContentDescription("Zap").assertDoesNotExist()
        // The author's npub and the note's nevent, to find them in another app (device feedback 2026-10-19).
        rule.onAllNodes(hasContentDescription("More") and hasClickAction()).onLast().performClick()
        tap("Copy npub")
        until { copied.size == 1 }
        rule.onAllNodes(hasContentDescription("More") and hasClickAction()).onLast().performClick()
        tap("Copy note link (nevent)")
        until { copied.size == 2 }
        check(copied[0].startsWith("npub1") && copied[1].startsWith("nevent1")) { copied.toString() }
        // Straight into the listener's Nostr app, at the author's profile (device feedback 2026-10-19).
        rule.onAllNodes(hasContentDescription("More") and hasClickAction()).onLast().performClick()
        tap("Open profile in Nostr app")
        until { copied.size == 3 }
        check(copied[2] == "open:" + copied[0]) { copied.toString() }
        check(kotlinx.coroutines.runBlocking { koin.get<space.sovereigncast.hopp.data.SettingsRepository>().current().nostrPublicNoted })
        // An answer's field opens right under the comment, in view (device feedback 2026-10-16).
        tap("Reply")
        waitFor("Replying to Me")
        rule.onNode(hasText("Write your answer…")).assertIsDisplayed()
        check(!has("Write a comment…")) { "the field at the top stays while answering" }
    }

    /** Maintainer 2026-10-24: the show's post on the episode above its comments, as Fountain shows it —
     *  marked as the show's, folded to five lines, liked and zapped like a comment; a new comment answers
     *  it, so it has no Reply of its own. */
    @Test
    fun theShowsPostIsPinnedAboveTheComments() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        val show = space.sovereigncast.hopp.nostr.KeyPair.generate()
        val alice = space.sovereigncast.hopp.nostr.KeyPair.generate()
        val item = "podcast:item:guid:ep-2"
        val text = "Ep 2 is out. In this episode Karsten explains why the main part starts at 12:34.\n\n" +
            (1..8).joinToString("\n\n") { "Topic $it: what we talked about, and what it means for you." } + "\n\nhttps://show.test/ep-2"
        val post = space.sovereigncast.hopp.nostr.Event.sign(show, 1, text, listOf(listOf("i", item)), 1_790_000_000)
        val comment = space.sovereigncast.hopp.nostr.Event.sign(alice, 1, "Great one", listOf(listOf("i", item)), 1_790_000_100)
        kotlinx.coroutines.runBlocking {
            val store = koin.get<space.sovereigncast.hopp.comments.CommentStore>()
            store.save(item, space.sovereigncast.hopp.comments.CommentStore.Seen(listOf(post, comment), emptyList(), emptySet(), 1_790_000_200, roots = setOf(post.id)))
            store.saveAuthors(mapOf(show.publicKeyHex to space.sovereigncast.hopp.comments.CommentAuthor("Test Show", null),
                alice.publicKeyHex to space.sovereigncast.hopp.comments.CommentAuthor("Alice", null)), 1_790_000_200)
        }
        tap("Ep 2"); waitFor("Play")
        // Kept from before: shown at once, no need to load.
        tap("Comments")
        waitFor("The show’s post")
        waitFor("Great one")
        check(has("Ep 2 is out", substring = true)) { screen().toString() }
        check(rule.onAllNodes(hasText("Reply")).fetchSemanticsNodes().size == 1) { "only the comment takes a Reply" }
        waitFor("more")
        snapshot("comments-pinned")
        tap("more")
        waitFor("less")
        snapshot("comments-pinned-open")
    }

    /** Each window as drawn, into build/screens — a look at a layout no assertion describes. */
    private fun snapshot(name: String) {
        java.io.File("build/screens").mkdirs()
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val views = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<android.view.View>
        rule.runOnIdle {
            views.forEachIndexed { i, v ->
                val bitmap = android.graphics.Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(bitmap))
                java.io.FileOutputStream("build/screens/$name-$i.png").use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    /** Device feedback 2026-10-16: the show's latest boosts, cycling under the
     *  player's controls, confused more than they told — they are gone. */
    @Test
    fun thePlayerNoLongerCyclesTheShowsBoosts() {
        rule.setContent { App() }
        tap("Discover"); subscribe("https://pod.test/sc.xml")
        waitFor("✓ Sovereigncast")
        tap("E296"); waitFor("Play")
        tap("Play")
        until { koin.get<space.sovereigncast.hopp.player.PlayerController>().current.value != null }
        koin.get<space.sovereigncast.hopp.player.PlayerController>().expand()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Community") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        check(!has("z · 21 sats", substring = true)) { "the latest-boosts line is back" }
    }

    // ── device feedback 2026-10-09 ─────────────────────────────────────────

    /** A horizontal swipe moves between the episode page's tabs. */
    @Test
    fun aSwipeChangesTheEpisodeTab() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Ep 2"); waitFor("Show notes")
        // Show notes → transcript, the next tab of this episode. The tabs come all at once
        // with the details (2026-10-17): wait for the notes themselves, laid out, first.
        rule.waitUntil(10_000) { has("(12:34) Hauptteil", substring = true) }
        rule.waitForIdle()
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

    /** With a Nostr identity a boost can go out as a comment too; the setting
     *  presets the switch (device feedback 2026-10-16). */
    @Test
    fun aBoostOffersNostrWithAnIdentity() {
        kotlinx.coroutines.runBlocking {
            koin.get<space.sovereigncast.hopp.identity.IdentityRepository>().create("Me")
            koin.get<space.sovereigncast.hopp.data.SettingsRepository>().update { it.copy(nostrBoosts = true) }
        }
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Ep 2"); waitFor("Show notes")
        tap("Boost")
        waitFor("Also as a comment on Nostr")
        rule.onNode(androidx.compose.ui.test.isToggleable() and androidx.compose.ui.test.isOn()).assertExists()
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

    /** A round at its goal (maintainer, 2026-10-23): a tick, and no boost or community page offered for it. */
    @Test
    fun aRoundAtItsGoalOffersNoMoreBoosts() {
        roundRaised = 15_120
        try {
            rule.setContent { App() }
            tap("Discover"); subscribe("https://pod.test/sc.xml")
            waitFor("E297 - BTC++ Berlin")
            waitFor("✓ Goal reached", substring = true)
            rule.onNode(hasText("E297 - BTC++ Berlin") and hasClickAction()).performClick()
            waitFor("Goal reached – the episode can’t come out any earlier.")
            check(!has("Open the community page")) { screen().toString() }
            check(rule.onAllNodes(hasText("Boost") and hasClickAction()).fetchSemanticsNodes().none { n ->
                n.boundsInRoot.top > rule.onNode(hasText("Goal reached – the episode can’t come out any earlier.")).fetchSemanticsNode().boundsInRoot.top
            }) { "a boost is still offered below the tick: ${screen()}" }
            back()
            tap("Home")
            waitFor("RELEASE BOOSTING · Nodesignal")
            waitFor("Goal reached", substring = true)
        } finally { roundRaised = 210 }
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
        // The tab's own list — the pager around it and the band of latest boosts scroll by index too.
        rule.onAllNodes(androidx.compose.ui.test.hasScrollToIndexAction() and
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .onLast().performScrollToNode(hasText("Blaubär"))
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
        rule.waitUntil(10_000) { has("Hopp collects nothing.", substring = true) }
        check(has("Podcast Index")) { screen().toString() }
        check(has("Hopp/0.0.1 (Android; Phone)", substring = true)) { screen().toString() }
        // One line a party; the details on a tap (device feedback 2026-10-16: too much text).
        check(!has("Tip")) { "details on the page itself" }
        tap("Podcast Index")
        waitFor("If you know a show's feed address", substring = true)
    }

    /** OPML import next to the export, in the settings (actionsslave, 2026-10-14). */
    @Test
    fun opmlIsImportedFromTheSettings() {
        val actions = object : PlatformActions {
            override fun pickOpml(onText: (String) -> Unit) =
                onText("""<opml version="2.0"><body><outline type="rss" text="Test Show" xmlUrl="https://pod.test/feed.xml"/></body></opml>""")
            override fun exportOpml(text: String) = Unit
        }
        rule.setContent { App(actions) }
        rule.onNode(hasContentDescription("Settings")).performClick()
        rule.onNode(hasText("Subscriptions") and hasClickAction()).performScrollTo().performClick()
        // Where other apps keep their export, then one button (maintainer, 2026-10-21).
        waitFor("AntennaPod")
        tap("Choose OPML file")
        waitFor("1 subscribed, 0 already there, 0 failed")
        back(); back()
        tap("Library"); waitFor("Test Show")
    }

    /** A tip on every card; green where Hopp sees it followed, a tap to where it is set. */
    @Test
    fun thePrivacyPageChecksWhatItCan() {
        var vpnOpened = 0
        val actions = object : PlatformActions {
            override fun pickOpml(onText: (String) -> Unit) = Unit
            override fun exportOpml(text: String) = Unit
            override fun openVpnSettings() { vpnOpened++ }
        }
        rule.setContent { App(actions) }
        rule.onNode(hasContentDescription("Settings")).performClick()
        rule.onNode(hasText("Privacy") and hasClickAction()).performScrollTo().performClick()
        waitFor("No VPN active · set one up")
        // What Hopp can check, together at the top (device feedback 2026-10-16).
        check(has("2 of 4 done") && has("fyyd and Apple Podcasts switched off")) { screen().toString() }
        rule.onAllNodes(hasText("No VPN active · set one up") and hasClickAction()).onFirst().performClick()
        check(vpnOpened == 1) { "VPN settings opened $vpnOpened times" }
        // Back from the system's settings with a VPN on: VPN and DNS turn green.
        testNetwork.value = space.sovereigncast.hopp.platform.NetworkPrivacy(vpn = true, privateDns = false)
        waitFor("VPN active")
        check(has("DNS goes through your VPN") && has("✓ 4 of 4 done")) { screen().toString() }

        // A name goes along with boosts: said so, and a tap leads to where it is set.
        kotlinx.coroutines.runBlocking { koin.get<space.sovereigncast.hopp.data.SettingsRepository>().update { it.copy(senderName = "Satoshi") } }
        waitFor("Your name “Satoshi” is sent along")
        check(has("3 of 4 done")) { screen().toString() }
        // The refresh setting is in the hosts' details, a tap from there.
        tap("Podcast hosts")
        waitFor("Background refresh every hour")
        rule.onNode(hasText("Background refresh every hour") and hasClickAction()).performClick()
        rule.waitUntil(10_000) { !has("Background refresh every hour") }
    }

    /** The name line leads to where the name is set. */
    @Test
    fun thePrivacyPageLeadsToTheName() {
        kotlinx.coroutines.runBlocking { koin.get<space.sovereigncast.hopp.data.SettingsRepository>().update { it.copy(senderName = "Satoshi") } }
        rule.setContent { App() }
        rule.onNode(hasContentDescription("Settings")).performClick()
        rule.onNode(hasText("Privacy") and hasClickAction()).performScrollTo().performClick()
        waitFor("Your name “Satoshi” is sent along")
        rule.onAllNodes(hasText("Your name “Satoshi” is sent along") and hasClickAction()).onFirst().performClick()
        waitFor("Wallet")
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

    /** Device feedback 2026-10-17: a chapter's picture full screen from its thumbnail, closed again. */
    @Test
    fun aChapterPictureOpensFullScreen() {
        rule.setContent { App() }
        tap("Discover"); subscribe()
        tap("Ep 1"); waitFor("Die Node")
        val full = androidx.compose.ui.test.SemanticsMatcher("opens the picture") {
            it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.OnClick) { null }?.label == "Show the picture full screen"
        }
        rule.onAllNodes(full).onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasContentDescription("Close the picture")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasContentDescription("Close the picture")).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasContentDescription("Close the picture")).fetchSemanticsNodes().isEmpty() }
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
        // Nostr, optional (2026-10-16): what it is and what Hopp does with it.
        waitFor("Nostr, if you like")
        check(has("Set up") && has("Restore")) { "the tour offers to set up Nostr, or restore from it, right away" }
        tap("Next")
        waitFor("Connect your wallet")
        check(has("Zeus", substring = true) && !has("Primal, Coinos", substring = true)) { "Primal's NWC does not do what Hopp needs" }
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
        // The last row of the overview, below a phone's fold since the Nostr identity joined it.
        rule.onNode(hasText("About Hopp") and hasClickAction()).performScrollTo().performClick()
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
        waitFor("After a zap")
        check(settings().boostFeedback == "errors") { "only failures by default" }
        // Each case on its own (device feedback 2026-10-19): quick boost, boost, zap.
        rule.onAllNodes(hasText("Always") and hasClickAction())[2].performClick()
        until { settings().zapFeedback == "always" }
        check(settings().boostFeedback == "errors" && settings().quickBoostFeedback == "errors") { settings().toString() }
    }

    /** Release Boosting at a subscribed show shows on Home, quietly; a tap opens the show at the round. */
    @Test
    fun homeShowsWhatRunsAtYourShows() {
        rule.setContent { App() }
        tap("Discover"); subscribe("https://pod.test/sc.xml")
        waitFor("✓ Sovereigncast")
        tap("Home")
        waitFor("Take part")
        waitFor("RELEASE BOOSTING · Nodesignal")
        // The open suggestion this listener has not voted for (device feedback 2026-10-12).
        waitFor("Topics awaiting your vote: 1")
        check(has("Your shows")) { screen().toString() }
        // "Vote" opens the Community tab, not the episodes (device feedback 2026-10-12).
        tap("Vote ›")
        waitFor("BIP110")
        back()
        waitFor("Take part")
        rule.onNode(hasText("E297 - BTC++ Berlin") and hasClickAction()).performClick()
        waitFor("✓ Sovereigncast")
        // Straight to the round's sheet, not only the show (device feedback 2026-10-22).
        waitFor("21 sats bring it out a minute earlier")
    }

    /** Single entries under "Take part" hide on their own, come back with Undo or from
     *  "Customize Home", and the show's page still shows them (device feedback 2026-10-22). */
    @Test
    fun takePartHidesOneEntryAtATime() {
        rule.setContent { App() }
        tap("Discover"); subscribe("https://pod.test/sc.xml")
        waitFor("✓ Sovereigncast")
        tap("Home")
        waitFor("RELEASE BOOSTING · Nodesignal")
        // This round only: the suggestions stay.
        rule.onAllNodes(hasText("E297 - BTC++ Berlin") and hasClickAction()).onFirst().performTouchInput { longClick() }
        waitFor("Hide this round")
        // Suggestions are offered only on a suggestion (device feedback 2026-10-23).
        check(!has("Hide suggestions from Nodesignal")) { screen().toString() }
        tap("Hide this round")
        until { !has("RELEASE BOOSTING · Nodesignal") }
        check(has("Topics awaiting your vote: 1")) { screen().toString() }
        check(settings().takePartHidden == listOf("rb:$SC_GUID/e297")) { settings().takePartHidden.toString() }
        // Undo brings it back at once.
        tap("Undo")
        waitFor("RELEASE BOOSTING · Nodesignal")
        check(settings().takePartHidden.isEmpty())
        // The whole show: "Take part" is empty and goes; "Your shows" keeps the show.
        rule.onAllNodes(hasText("Topics awaiting your vote: 1") and hasClickAction()).onFirst().performTouchInput { longClick() }
        tap("Hide everything from Nodesignal")
        until { !has("Take part") }
        check(has("Your shows")) { screen().toString() }
        // Back from "Customize Home".
        rule.onNode(hasContentDescription("Customize Home")).performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Show again"))
        waitFor("Hidden under “Take part”")
        check(has("Everything"))
        tap("Show again")
        until { settings().takePartHidden.isEmpty() }
    }

    // ── device feedback 2026-10-10 (later) ─────────────────────────────────

    /** With a wallet connected, the tour does not ask for one. */
    /** Nostr is made in Hopp itself — the tour leads there (maintainer, 2026-10-16). */
    @Test
    fun theTourSetsUpNostr() {
        tourNotDone()
        rule.setContent { App() }
        waitFor("Welcome to Hopp")
        repeat(4) { tap("Next") }
        waitFor("Nostr, if you like")
        tap("Set up")
        waitFor("Join the conversation on Nostr")
        until { settings().onboardingDone }
    }

    /** Sync came (2026-10-20): no longer announced as coming, and who had Hopp before finds the way back. */
    @Test
    fun theTourLeadsBackToALibraryOnNostr() {
        tourNotDone()
        rule.setContent { App() }
        waitFor("Welcome to Hopp")
        repeat(4) { tap("Next") }
        waitFor("Nostr, if you like")
        check(!has("Soon")) { "sync is no longer to come" }
        check(has("your library and progress on every device", substring = true))
        tap("Restore")
        // The ways in with an existing account, open at once, saying what the key is for.
        waitFor("nsec1… or ncryptsec1…")
        waitFor("Hopp then brings your library back", substring = true)
        until { settings().onboardingDone }
    }

    /** Left for a page from the tour, back comes back to the tour where it was (maintainer, 2026-10-21). */
    @Test
    fun backFromAPageTheTourOpenedReturnsToTheTour() {
        tourNotDone()
        rule.setContent { App() }
        waitFor("Welcome to Hopp")
        tap("I already use Hopp")
        waitFor("nsec1… or ncryptsec1…")
        // Back — on a device the sign-in sheet takes the first one — leads to the tour, not Home.
        repeat(2) { if (!has("Welcome to Hopp")) { systemBack(); rule.waitForIdle() } }
        waitFor("Welcome to Hopp")
        check(!has("Join the conversation on Nostr")) { "the Nostr page stayed on top of the tour" }
        // From the second page, by the arrow: back on the second page.
        tap("Next")
        tap("I’m coming from another podcast app")
        waitFor("AntennaPod")
        rule.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        waitFor("Subscribe to shows")
        tap("Skip")
        until { settings().onboardingDone }
    }

    /** Who uses Hopp already need not page through the tour (maintainer, 2026-10-21). */
    @Test
    fun theWelcomePageLeadsBackToALibraryOnNostr() {
        tourNotDone()
        rule.setContent { App() }
        waitFor("Welcome to Hopp")
        tap("I already use Hopp")
        waitFor("nsec1… or ncryptsec1…")
        until { settings().onboardingDone }
    }

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
        tap("Next")
        waitFor("Nostr, if you like")
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
        check(scWebsiteOpened("Discover", "What Sovereigncast brings", "More about Sovereigncast") == "https://sovereigncast.space/")
    }

    /** The badge's sheet sends a listener to the website in Hopp's own
     *  language (device feedback 2026-10-11) … */
    @Test @Config(qualifiers = "de-w411dp-h891dp")
    fun scWebsiteFollowsHoppsLanguage() {
        check(scWebsiteOpened("Entdecken", "Was Sovereigncast mitbringt", "Mehr über Sovereigncast") == "https://sovereigncast.space/de/")
    }

    /** … and in English where Hopp has no translation — Spanish here, which
     *  the website has, but Hopp would show English around the link. */
    @Test @Config(qualifiers = "es-w411dp-h891dp")
    fun scWebsiteFallsBackToEnglish() {
        check(scWebsiteOpened("Discover", "What Sovereigncast brings", "More about Sovereigncast") == "https://sovereigncast.space/")
    }

    /** Hopp's language apart from the phone's: an entry where the system
     *  offers it (Android 13+), opening the system's page. */
    @Test
    fun languageEntryOpensTheSystemChoice() {
        var opened = 0
        val actions = object : PlatformActions {
            override fun pickOpml(onText: (String) -> Unit) = Unit
            override fun exportOpml(text: String) = Unit
            override val canPickLanguage get() = true
            override fun openLanguageSettings() { opened++ }
        }
        rule.setContent { App(actions) }
        rule.onNode(hasContentDescription("Settings")).performClick()
        waitFor("Language")
        check(has("English")) { screen().toString() }
        tap("Language")
        check(opened == 1) { "opened $opened times" }
    }

    /** The Sovereigncast filter is there at once, not when the list has
     *  arrived (device feedback 2026-10-11) — on, it waits for the list. */
    @Test
    fun theSovereigncastFilterIsAlwaysThere() {
        rule.setContent { App() }
        tap("Discover")
        // The directory answers after 1.5 s (the mock's delay); the chip does not wait.
        check(rule.onAllNodes(hasText("✓ Sovereigncast") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) { screen().toString() }
        rule.onAllNodes(hasText("✓ Sovereigncast") and hasClickAction()).onFirst().performClick()
        rule.waitUntil(10_000) { has("Nodesignal", substring = true) }
    }

    /** Search → the badge → the sheet → its link; what the link opened. */
    private fun scWebsiteOpened(discover: String, title: String, more: String): String? {
        var opened: String? = null
        val uris = object : androidx.compose.ui.platform.UriHandler { override fun openUri(uri: String) { opened = uri } }
        rule.setContent { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides uris) { App() } }
        tap(discover)
        rule.onNode(hasSetTextAction()).performTextInput("nodesignal")
        waitFor("Nodesignal")
        rule.onAllNodes(hasText("✓ Sovereigncast") and hasClickAction()).onLast().performClick()
        waitFor(title)
        tap(more)
        return opened
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
        repeat(5) { tap("Next") }
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
            repeat(5) { tap("Next") }
            waitFor("Connect your wallet")
            rule.waitForIdle()
            Thread.sleep(2_000)
            rule.waitForIdle()
            check(!has("nwc.ereignishorizont.xyz ↗") && !has("What NWC is and how to set it up:")) { screen().toString() }
        } finally { nwcDown = false }
    }
}
