package space.sovereigncast.hopp.value

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.QueueRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.data.SubscribeResult
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.player.RecordingEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Package 4: wallet, boosts and streaming sats (ADR 0005, 0014). */
class ValueServiceTest {
    private val host = "02" + "a".repeat(64)
    private val cohost = "02" + "b".repeat(64)
    private val artist = "02" + "c".repeat(64)
    private val feed = """<?xml version="1.0"?><rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel>
        <title>Show</title><podcast:guid>show-guid</podcast:guid>
        <podcast:value type="lightning" method="keysend" suggested="0.00000060000">
          <podcast:valueRecipient name="Host" type="node" address="$host" split="90"/>
          <podcast:valueRecipient name="Cohost" type="node" address="$cohost" split="10"/>
          <podcast:valueTimeSplit startTime="600" duration="120" remotePercentage="50">
            <podcast:remoteItem feedGuid="music-guid" itemGuid="song-1" feedUrl="https://music.test/feed.xml"/>
          </podcast:valueTimeSplit>
        </podcast:value>
        <item><title>Ep</title><guid>ep-1</guid><enclosure url="https://cdn.test/1.mp3" type="audio/mpeg"/></item>
        </channel></rss>"""
    private val music = """<?xml version="1.0"?><rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel>
        <title>Band</title><podcast:guid>music-guid</podcast:guid>
        <item><title>Song</title><guid>song-1</guid><enclosure url="https://music.test/1.mp3" type="audio/mpeg"/>
          <podcast:value type="lightning" method="keysend"><podcast:valueRecipient name="Artist" type="node" address="$artist" split="100"/></podcast:value>
        </item></channel></rss>"""
    private val http = HttpClient(MockEngine { req ->
        when (req.url.encodedPath) {
            "/feed.xml" -> if (req.url.host == "music.test") respond(music) else respond(feed)
            else -> respond("", HttpStatusCode.NotFound)
        }
    })
    private var now = 1_780_000_000L
    private val db = HoppDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.create(it) })
    private val settings = SettingsRepository(db)
    private val podcasts = PodcastRepository(db, FeedFetcher(http), { now })
    private val episodes = EpisodeRepository(db, http, { now })
    private val relay = FakeRelay()
    private var walletMethods = listOf("pay_invoice", "pay_keysend", "get_balance", "get_info")
    private val fake by lazy { FakeWallet(relay, methods = walletMethods).also { it.publishInfo() } }
    private val secrets = MemorySecretStore()
    private val wallet = WalletRepository(secrets, http, { now }, relay, timeout = kotlin.time.Duration.parse("5s"))
    private val value = ValueService(db, wallet, episodes, podcasts, settings, LnAddressClient(http), FeedFetcher(http), null, { now }, AppInfo("0.0.1", "JVM", "Test"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun stop() = scope.cancel()

    private fun uri() = "nostr+walletconnect://${fake.keys.publicKeyHex}?relay=wss://relay.test&secret=${KeyPair.generate().secretKey.toHex()}"
    private fun test(block: suspend () -> Unit) { runBlocking { withTimeout(30_000) { block() } } }
    private suspend fun episode(): EpisodeItem {
        val id = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId
        return episodes.ofPodcast(id).first().single()
    }
    private fun keysends() = fake.requests.filter { it.first == "pay_keysend" }.map {
        it.second["pubkey"]!!.jsonPrimitive.content to it.second["amount"]!!.jsonPrimitive.long / 1000
    }

    @Test
    fun aWalletIsKeptOnlyOnceItAnswered() = test {
        assertFailsWith<IllegalArgumentException> { wallet.connect("nostr+walletconnect://nonsense") }
        assertNull(wallet.wallet.value)
        assertNull(secrets.get("nwc_uri"))
        val w = wallet.connect(uri())
        assertTrue(w.canKeysend && w.canPayInvoice)
        assertTrue(secrets.get("nwc_uri")!!.startsWith("nostr+walletconnect://"))
        assertEquals(21_000_000L, wallet.balanceMsat())
        wallet.disconnect()
        assertNull(wallet.wallet.value)
        assertNull(secrets.get("nwc_uri"))
    }

    @Test
    fun aBoostIsSplitIncludingTheSongPlayingAndLogged() = test {
        wallet.connect(uri())
        val ep = episode()
        // At 10:30 the song plays: half of the boost goes to the artist.
        val results = value.boost(ep, 630, 1_000_000, "Danke!")
        assertTrue(results.all { it is ShareResult.Paid }, results.describe())
        assertEquals(listOf(host to 450L, cohost to 50L, artist to 500L), keysends())
        val log = value.payments.first().single()
        assertEquals(1_000_000L, log.amountMsat)
        assertEquals("Danke!", log.message)
        assertEquals(listOf("paid", "paid", "paid"), log.lines.map { it.status })
    }

    @Test
    fun withoutKeysendTheDialogSaysWhoCannotBeReached() = test {
        walletMethods = listOf("pay_invoice", "get_info")
        wallet.connect(uri())
        val plan = value.plan(episode(), 30, 100_000) as BoostPlan.Ready
        assertEquals(listOf("keysend", "keysend"), plan.shares.map { it.unpayable })
    }

    @Test
    fun streamingPaysWholeSatsSplitByTheFeed() = test {
        wallet.connect(uri())
        settings.update { it.copy(streaming = true, streamRateSats = 60) } // one sat per second
        val ep = episode()
        value.accrue(ep, 30_000, 500) // half a sat — stays owed
        value.settle()
        assertTrue(keysends().isEmpty(), "nothing below a whole sat")
        value.accrue(ep, 60_000, 29_500)
        value.settle()
        assertEquals(listOf(host to 27L, cohost to 3L), keysends())
        assertEquals(30_000L, value.streamedTodayMsat.first())
        assertEquals(0L, value.pendingMsat.first())
    }

    @Test
    fun theBudgetStopsStreamingAndSaysSo() = test {
        wallet.connect(uri())
        settings.update { it.copy(streaming = true, streamRateSats = 60, dailyBudgetSats = 10) }
        value.accrue(episode(), 60_000, 30_000)
        value.settle()
        assertEquals(listOf(cohost to 3L), keysends(), "27 sats for the host would exceed the 10 left")
        assertTrue(value.overBudget.value)
        assertEquals(27_000L, value.pendingMsat.first(), "kept, not dropped")
    }

    @Test
    fun whatWasOwedOfflineIsPaidLaterWithANotice() = test {
        wallet.connect(uri())
        settings.update { it.copy(streaming = true, streamRateSats = 60) }
        value.accrue(episode(), 60_000, 10_000)
        fake.failWith = "INTERNAL" to "no route"
        value.settle()
        assertEquals(10_000L, value.pendingMsat.first())
        assertTrue(value.payments.first().isEmpty())
        fake.failWith = null
        val notice = scope.async { value.notices.first() }
        delay(50)
        value.settle()
        assertEquals(StreamNotice(10, 1), withTimeout(5_000) { notice.await() })
        assertEquals(0L, value.pendingMsat.first())
    }

    @Test
    fun nothingAccruesWhileStreamingIsOff() = test {
        wallet.connect(uri())
        value.accrue(episode(), 60_000, 30_000)
        assertEquals(0L, value.pendingMsat.first())
    }

    @Test
    fun thePlayerAccruesMediaTimeNotJumps() = test {
        wallet.connect(uri())
        settings.update { it.copy(streaming = true, streamRateSats = 60) }
        val ep = episode()
        val engine = RecordingEngine()
        val player = PlayerController(engine, episodes, podcasts, QueueRepository(db), settings, scope, value) { 0 }
        player.play(ep)
        withTimeout(5_000) { while (engine.loaded.isEmpty() || player.current.value == null) delay(10) }
        suspend fun at(ms: Long, speed: Float = 1f) { engine.s.value = engine.s.value.copy(positionMs = ms, isPlaying = true, speed = speed); delay(40) }
        for (t in 1..10) at(t * 1_000L)            // 10 s heard
        at(300_000)                                  // a jump: not heard
        for (t in 1..5) at(300_000 + t * 2_000L, 2f) // 2×: 10 s of content in 5 s
        withTimeout(5_000) { while (value.pendingMsat.first() < 20_000) delay(20) }
        delay(200)
        assertEquals(20_000L, value.pendingMsat.first(), "20 s of content at one sat a second")
    }

    /** The lock-screen button: the set amount at once, never past the budget. */
    @Test
    fun aQuickBoostSendsTheSetAmountWithinTheBudget() = test {
        val ep = episode()
        assertEquals(QuickBoost.NoWallet, value.quickBoost(ep, 30))
        wallet.connect(uri())
        val sent = value.quickBoost(ep, 30) as QuickBoost.Sent
        assertEquals(100L, sent.paidSats)
        assertEquals("Show", sent.show)
        assertEquals(listOf(host to 90L, cohost to 10L), keysends())
        settings.update { it.copy(dailyBudgetSats = 150) }
        assertEquals(QuickBoost.OverBudget, value.quickBoost(ep, 30), "100 more would make 200 of 150")
        settings.update { it.copy(quickBoostSats = 0) }
        assertEquals(QuickBoost.Off, value.quickBoost(ep, 30))
    }

    /** Device feedback 2026-10-08: a boost to the whole show — its block, no
     *  episode, no song from a time split. */
    @Test
    fun theWholeShowCanBeBoosted() = test {
        wallet.connect(uri())
        val podcastId = episode().podcastId
        val plan = value.planShow(podcastId, 100_000) as BoostPlan.Ready
        assertEquals(listOf(90_000L, 10_000L), plan.shares.map { it.amountMsat })
        val results = value.boostShow(podcastId, 100_000, "Für alles")
        assertTrue(results.all { it is ShareResult.Paid })
        assertEquals(listOf(host to 90L, cohost to 10L), keysends())
        val logged = value.payments.first().single()
        assertEquals("Show", logged.podcastTitle)
        assertNull(logged.episodeTitle)
        // The boostagram names the show but no episode.
        val tlv = fake.requests.first().second.toString()
        assertTrue("episode_guid" !in tlv || "null" in tlv)
    }

    /** The full amount reaches the recipients; the wallet's routing fee comes
     *  on top and is logged apart. */
    @Test
    fun aBoostArrivesInFullWithTheFeeOnTop() = test {
        wallet.connect(uri())
        val ep = episode()
        value.boost(ep, 30, 21_000, null)
        assertEquals(listOf(host to 19L, cohost to 2L), keysends(), "21 sats at 90/10: 18.9 + 2.1 rounds to 19 + 2")
        val lines = value.payments.first().single().lines
        assertEquals(21_000L, lines.sumOf { it.msat })
        assertTrue(lines.all { it.feesMsat == 1000L }, "the fake wallet reports a 1-sat fee per payment")
    }
}
