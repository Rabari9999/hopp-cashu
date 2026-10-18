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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
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
import kotlin.time.Duration.Companion.milliseconds
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
    private val donee = "02" + "d".repeat(64)
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
            "/.well-known/keysend/donate" -> respond("""{"tag":"keysend","pubkey":"$donee"}""")
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

    /** Parts of a sat wait while the episode is being heard, and are rounded
     *  up once it is done — heard to its end, or not for 24 h (user decision
     *  2026-10-12); the sheet says which episode owes and why. */
    @Test
    fun partsOfASatAreRoundedUpOnceTheEpisodeIsDone() = test {
        wallet.connect(uri())
        settings.update { it.copy(streaming = true, streamRateSats = 60) } // one sat per second
        val ep = episode()
        episodes.savePosition(ep.id, 2_500) // heard just now
        value.accrue(ep, 2_500, 2_500)      // 2.25 to the host, 0.25 to the cohost
        assertEquals(PendingReason.NextRound, value.pendingByEpisode().single().reason)
        value.settle()
        assertEquals(listOf(host to 2L), keysends(), "whole sats only while it is being heard")
        val left = value.pendingByEpisode().single()
        assertEquals(500L, left.msat)
        assertEquals(PendingReason.PartsOfASat, left.reason)
        assertEquals("Ep", left.item?.title)

        // Not heard for a day: rounded up — one sat each, never more.
        now += 25 * 3600
        value.settle()
        assertEquals(listOf(host to 2L, host to 1L, cohost to 1L), keysends())
        assertTrue(value.pendingByEpisode().isEmpty())
        assertEquals(0L, value.pendingMsat.first())
    }

    @Test
    fun anEpisodeHeardToItsEndPaysItsPartsAtOnce() = test {
        wallet.connect(uri())
        settings.update { it.copy(streaming = true, streamRateSats = 60) }
        val ep = episode()
        episodes.savePosition(ep.id, 500)
        value.accrue(ep, 500, 500) // 0.45 + 0.05
        value.settle()
        assertTrue(keysends().isEmpty())
        value.settle(roundUp = setOf(ep.id)) // what the player does at the end
        assertEquals(listOf(host to 1L, cohost to 1L), keysends())
    }

    /** Batches follow listening (user decision 2026-10-11): none before it
     *  starts, one per round while playing, the round under way still paid
     *  after a pause — then the timer rests. Here a round is 400 ms, not 3 min. */
    @Test
    fun streamingBatchesFollowListening() = test {
        wallet.connect(uri())
        settings.update { it.copy(streaming = true, streamRateSats = 60) } // one sat per second
        val ep = episode()
        val engine = RecordingEngine()
        val player = PlayerController(engine, episodes, podcasts, QueueRepository(db), settings, scope, value, settleEvery = 400.milliseconds) { 0 }
        assertNull(value.secondsToNextSettle(), "no round before listening")
        player.play(ep)
        withTimeout(5_000) { while (engine.loaded.isEmpty() || player.current.value == null) delay(10) }
        suspend fun at(ms: Long, playing: Boolean = true) { engine.s.value = engine.s.value.copy(positionMs = ms, isPlaying = playing); delay(40) }
        for (t in 1..5) at(t * 1_000L) // 5 s heard, the round started with it
        assertTrue(value.secondsToNextSettle() != null, "a round runs while listening")
        at(5_000, playing = false)      // paused within the round
        withTimeout(5_000) { while (keysends().isEmpty()) delay(20) }
        assertEquals(listOf(host to 4L), keysends(), "4.5 sats to the host: the whole ones went out after the pause")
        delay(800)
        assertNull(value.secondsToNextSettle(), "paused: the timer rests")
        assertEquals(1, keysends().size, "and pays nothing more")
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

    /** The streaming sheet (device feedback 2026-10-11): what waits for whom,
     *  heard and not yet split as well as owed, and when the next batch goes. */
    @Test
    fun whatStreamingSetAsideIsShownPerRecipient() = test {
        wallet.connect(uri())
        settings.update { it.copy(streaming = true, streamRateSats = 60) } // one sat per second
        val ep = episode()
        value.accrue(ep, 30_000, 2_500) // 2.5 sats: 2.25 to the host, 0.25 to the cohost
        assertEquals(listOf(PendingShare("Host", 2_250), PendingShare("Cohost", 250)), value.pendingFor(ep.id))
        value.settle()
        // Whole sats went out; the parts of one wait.
        assertEquals(listOf(host to 2L), keysends())
        assertEquals(listOf(PendingShare("Host", 250), PendingShare("Cohost", 250)), value.pendingFor(ep.id))
        assertEquals(500L, value.pendingMsat.first())
        assertNull(value.secondsToNextSettle())
        value.settleDueIn(300)
        assertEquals(300L, value.secondsToNextSettle())
        now += 120
        assertEquals(180L, value.secondsToNextSettle())
    }

    /** A boost from the sheet — player, episode, show — is reported like the
     *  quick boost (device feedback 2026-10-11): by default only when it goes wrong. */
    @Test
    fun aBoostFromTheSheetIsReportedAsTheListenerChose() = test {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val shown = java.util.concurrent.CopyOnWriteArrayList<QuickBoost>()
            val vm = space.sovereigncast.hopp.ui.value.ValueBarViewModel(value, wallet, settings, BoostReports(settings, { shown += it }, scope))
            val ep = episode()
            suspend fun until(check: () -> Boolean) = withTimeout(10_000) { while (!check()) delay(20) }

            // No wallet: nobody gets anything — worth a notification.
            vm.send(ep, 30, 100, "", "")
            until { shown.isNotEmpty() }
            assertTrue(!shown.single().ok, shown.toString())

            // Paid in full: by default, nothing to say.
            wallet.connect(uri())
            vm.resetBoost(); vm.send(ep, 30, 100, "", "")
            until { vm.boost.value is space.sovereigncast.hopp.ui.value.BoostState.Done }
            delay(300)
            assertEquals(1, shown.size, shown.toString())

            // "Always": the success too.
            settings.update { it.copy(boostFeedback = space.sovereigncast.hopp.data.Settings.FEEDBACK_ALWAYS) }
            vm.resetBoost(); vm.send(ep, 30, 100, "", "")
            until { shown.size == 2 }
            assertTrue(shown[1].ok && (shown[1] as QuickBoost.Sent).show == "Show", shown.toString())
        } finally {
            Dispatchers.resetMain()
        }
    }

    /** Security review 2026-10-10, M-05: two sub-wallets on one node are two
     *  recipients — streamed, each gets its own share with its own custom record. */
    @Test
    fun subWalletsOnOneNodeAreStreamedApart() = test {
        val subs = """<?xml version="1.0"?><rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel>
            <title>Subs</title><podcast:guid>subs-guid</podcast:guid>
            <podcast:value type="lightning" method="keysend">
              <podcast:valueRecipient name="Anna" type="node" address="$host" customKey="696969" customValue="anna" split="50"/>
              <podcast:valueRecipient name="Ben" type="node" address="$host" customKey="696969" customValue="ben" split="50"/>
            </podcast:value>
            <item><title>Ep</title><guid>subs-1</guid><enclosure url="https://cdn.test/s.mp3" type="audio/mpeg"/></item>
            </channel></rss>"""
        val local = HttpClient(MockEngine { respond(subs) })
        val pods = PodcastRepository(db, FeedFetcher(local), { now })
        val id = (pods.subscribe("https://subs.test/feed.xml") as SubscribeResult.Subscribed).podcastId
        val ep = episodes.ofPodcast(id).first().single()
        wallet.connect(uri())
        settings.update { it.copy(streaming = true, streamRateSats = 60) }
        value.accrue(ep, 4_000, 4_000) // 4 sats: 2 each
        assertEquals(listOf(PendingShare("Anna", 2_000), PendingShare("Ben", 2_000)), value.pendingFor(ep.id))
        value.settle()
        val sent = fake.requests.filter { it.first == "pay_keysend" }.map { req ->
            val records = req.second["tlv_records"]!!.jsonArray.associate { it.jsonObject["type"]!!.jsonPrimitive.long to it.jsonObject["value"]!!.jsonPrimitive.content }
            records[696969L]!!.chunked(2).map { it.toInt(16).toChar() }.joinToString("") to req.second["amount"]!!.jsonPrimitive.long / 1000
        }
        assertEquals(setOf("anna" to 2L, "ben" to 2L), sent.toSet())
    }

    /** Security report 2026-10-10, H-04: a streamed share whose payment got no
     *  answer is not paid again by the next batch; the history says "unknown". */
    @Test
    fun aStreamedShareWithoutAnswerIsNotPaidAgain() = test {
        wallet.connect(uri())
        settings.update { it.copy(streaming = true, streamRateSats = 60) }
        val ep = episode()
        value.accrue(ep, 2_500, 2_500) // 2.25 to the host, 0.25 to the cohost
        fake.silentMethod = "pay_keysend"
        value.settle()   // waits out the wallet's timeout (5 s here)
        value.settle()
        assertEquals(1, fake.requests.count { it.first == "pay_keysend" }, "paid again: ${fake.requests.map { it.first }}")
        assertEquals("unknown", value.payments.first().single().lines.single().status)
        // Perhaps paid, so off today's budget (security audit 2026-10-18, M-03).
        assertEquals(2_000L, value.spentTodayMsat.first())
        // Only the cohost's quarter sat still waits.
        assertEquals(listOf(PendingShare("Cohost", 250)), value.pendingFor(ep.id))
    }

    /** A process killed after the payment was marked and before the answer came
     *  back (M1): the next launch settles what was on the way and does not
     *  keysend it again. */
    @Test
    fun aShareLeftInFlightIsNotPaidAgain() = test {
        wallet.connect(uri())
        settings.update { it.copy(streaming = true, streamRateSats = 60) }
        val ep = episode()
        value.accrue(ep, 2_500, 2_500)
        wallet.disconnect()
        value.settle() // splits the debt, and with no wallet sends nothing
        markInFlight(host, 2_000)
        wallet.connect(uri())
        value.settle()
        assertEquals(0, keysends().count { it.first == host }, "paid a share that was already in flight")
        assertEquals("unknown", value.payments.first().single().lines.single { it.address == host }.status)
        assertEquals(250L, owed(host), "only what was on the way is settled")
        assertEquals(2_000L, value.spentTodayMsat.first(), "what was on the way counts against the budget")
    }

    /** Code review 2026-10-17: what was heard after the crash is not written off
     *  with the share that was in flight — it is paid in the next batch. */
    @Test
    fun whatWasHeardSinceTheCrashIsStillPaid() = test {
        wallet.connect(uri())
        settings.update { it.copy(streaming = true, streamRateSats = 60) } // one sat per second
        val ep = episode()
        value.accrue(ep, 2_500, 2_500) // 2.25 to the host
        wallet.disconnect()
        value.settle()
        markInFlight(host, 2_000)
        wallet.connect(uri())
        value.accrue(ep, 12_500, 10_000) // 9 more to the host, heard after the crash
        value.settle()
        assertEquals(9_250L, owed(host))
        value.settle()
        assertEquals(listOf(9L), keysends().filter { it.first == host }.map { it.second })
    }

    /** Code review 2026-10-17: a batch cancelled before the request went out
     *  (here while the relay connects) leaves no marker — the share stays owed
     *  and is paid, not written off as maybe paid. */
    @Test
    fun aBatchCancelledBeforeSendingKeepsTheShareOwed() = test {
        var hang = false
        val connector = space.sovereigncast.hopp.nostr.RelayConnector { url -> if (hang) kotlinx.coroutines.awaitCancellation() else relay.connect(url) }
        val w = WalletRepository(MemorySecretStore(), http, { now }, connector, timeout = kotlin.time.Duration.parse("5s"))
        val v = ValueService(db, w, episodes, podcasts, settings, LnAddressClient(http), FeedFetcher(http), null, { now }, AppInfo("0.0.1", "JVM", "Test"))
        w.connect(uri())
        settings.update { it.copy(streaming = true, streamRateSats = 60) }
        val ep = episode()
        v.accrue(ep, 2_500, 2_500)
        hang = true
        val batch = scope.async { v.settle() }
        withTimeout(5_000) { while (rows().none { ValueService.inFlightMsat(it.last_error) != null }) delay(10) }
        batch.cancel()
        runCatching { batch.await() }
        assertTrue(rows().none { it.last_error != null }, "the marker stayed: ${rows().map { it.last_error }}")
        hang = false
        v.settle()
        assertEquals(listOf(host to 2L), keysends())
    }

    /** ADR 0009: the App Store binary has no app split, even with a percent saved. */
    @Test
    fun theAppStoreBinaryLeavesTheAppSplitOut() = test {
        wallet.connect(uri())
        val ep = episode()
        settings.update { it.copy(supportHoppPercent = 5) }
        val appStore = ValueService(db, wallet, episodes, podcasts, settings, LnAddressClient(http), FeedFetcher(http), null, { now }, AppInfo("0.0.1", "JVM", "Test"), paysForContent = false)
        val plan = appStore.plan(ep, 30, 100_000) as BoostPlan.Ready
        assertTrue(plan.shares.none { it.recipient.name == ValueService.SUPPORT_NAME })
        assertEquals(100_000L, plan.shares.sumOf { it.amountMsat }, "the whole amount to the show")
    }

    private fun rows() = db.streamingQueries.owedWithHeard("lightning").executeAsList()
    private fun owed(node: String) = rows().filter { node in it.recipient_json }.sumOf { it.owed_msat }
    private fun markInFlight(node: String, msat: Long) = rows().filter { node in it.recipient_json }
        .forEach { db.streamingQueries.markSending(ValueService.inFlightMarker(msat), it.episode_id, it.recipient_key, it.rail) }

    /** Security audit 2026-10-18, M-03: the routing fee is known only after a
     *  payment, so a payment leaves room for it — the budget is not exceeded by it. */
    @Test
    fun aPaymentLeavesRoomForItsFee() = test {
        val ep = episode()
        wallet.connect(uri())
        settings.update { it.copy(quickBoostSats = 100, dailyBudgetSats = 100) }
        assertEquals(QuickBoost.OverBudget, value.quickBoost(ep, 30), "100 sats and the fee do not fit 100")
        settings.update { it.copy(dailyBudgetSats = 101) }
        assertTrue(value.quickBoost(ep, 30) is QuickBoost.Sent)
    }

    /** Security report 2026-10-10, H-03: ten quick boosts at once, budget for one — one is paid. */
    @Test
    fun parallelQuickBoostsStayWithinTheBudget() = test {
        val ep = episode()
        wallet.connect(uri())
        settings.update { it.copy(quickBoostSats = 100, dailyBudgetSats = 150) }
        val results = (1..10).map { scope.async { value.quickBoost(ep, 30) } }.map { it.await() }
        assertEquals(1, results.count { it is QuickBoost.Sent }, results.toString())
        assertEquals(9, results.count { it == QuickBoost.OverBudget }, results.toString())
        assertEquals(listOf(host to 90L, cohost to 10L), keysends())
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

    /** Device feedback 2026-10-15: a show's `lightning:` funding link paid
     *  inside Hopp — all of it to that address, logged as the show's boost. */
    @Test
    fun aFundingAddressIsPaidInHopp() = test {
        wallet.connect(uri())
        val ep = episode()
        val plan = value.planDonation("donate@alby.test", 5_000) as BoostPlan.Ready
        assertEquals(listOf("donate@alby.test" to 5_000L), plan.shares.map { it.recipient.address to it.amountMsat })
        value.donate(ep.podcastId, "donate@alby.test", 5_000, "Danke!")
        assertEquals(listOf(donee to 5L), keysends())
        val logged = value.payments.first().single()
        assertEquals(5_000L, logged.amountMsat)
        assertEquals("Danke!", logged.message)
    }

    /** Device feedback 2026-10-15: routing fees leave the wallet too and count
     *  against today's budget. */
    @Test
    fun routingFeesCountAgainstTheBudget() = test {
        wallet.connect(uri())
        settings.update { it.copy(dailyBudgetSats = 100) }
        value.boost(episode(), 30, 21_000, null)
        assertEquals(23_000L, value.spentTodayMsat.first(), "21 sats and a 1-sat fee for each of the two payments")
        assertEquals(77_000L, value.budgetLeftMsat.first())
    }

    /** "Support Hopp" (ADR 0005): off by default; when chosen, a visible share
     *  taken from the boost, not added to it. */
    @Test
    fun supportingHoppIsAVisibleShareOfTheSameAmount() = test {
        wallet.connect(uri())
        val ep = episode()
        val off = value.plan(ep, 30, 100_000) as BoostPlan.Ready
        assertTrue(off.shares.none { it.recipient.name == ValueService.SUPPORT_NAME })
        settings.update { it.copy(supportHoppPercent = 5) }
        val on = value.plan(ep, 30, 100_000) as BoostPlan.Ready
        assertEquals(5_000L, on.shares.single { it.recipient.name == ValueService.SUPPORT_NAME }.amountMsat)
        assertEquals(100_000L, on.shares.sumOf { it.amountMsat }, "still exactly the chosen amount")
    }
}
