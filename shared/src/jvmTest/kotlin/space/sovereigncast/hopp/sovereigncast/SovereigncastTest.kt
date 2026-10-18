package space.sovereigncast.hopp.sovereigncast

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.data.SubscribeResult
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.ui.sovereigncast.CommunityViewModel
import space.sovereigncast.hopp.ui.sovereigncast.ScPay
import space.sovereigncast.hopp.value.FakeRelay
import space.sovereigncast.hopp.value.FakeWallet
import space.sovereigncast.hopp.value.LnAddressClient
import space.sovereigncast.hopp.value.TestInvoices
import space.sovereigncast.hopp.value.ValueService
import space.sovereigncast.hopp.value.WalletRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** listener-v1.yaml's example for the leaderboard. */
private const val LEADERBOARD = """{"month":"2026-10",
  "top_boosts":[{"rank":1,"amount_sats":21000,"sender":"Satoshi","message":"Great episode!","episode":{"episode_guid":"9cd0c018-4cf8-49a2-800a-cac3d2129f5e","title":"E296 - Keine Node ist auch keine Lösung"}}],
  "recent_boosts":[{"amount_sats":2100,"sender":"Hal","message":"","episode":{"episode_guid":null,"title":""}}],
  "alltime_boosters":[{"rank":1,"sender":"Satoshi","trend":"same","prev_rank":1,"legendary":true,"medals":{"gold":4,"silver":1,"bronze":0}}],
  "poll_after_seconds":300}"""

/** The slim Sovereigncast layer of phase 1 (ADR 0002, 0010, 0018). */
class SovereigncastTest {
    private val guid = "e8df0b13-47de-544a-99b7-ec7cbd960a16"
    private var marker = "https://pm.test"
    private var reportedFeed = "https://pod.test/feed.xml"
    private var instanceDown = false
    private var showKnown = true
    private var invoiceAmountMsat: Long? = null
    /** The hash the instance names for its invoice; the test invoice's own unless a test lies. */
    private var hashInJson = TestInvoices.PAYMENT_HASH
    private var statusCalls = 0
    /** When set, the instance reports this status instead of pending-then-paid. */
    private var paymentStatus: String? = null
    private val bodies = mutableListOf<String>()

    private fun feed() = """<?xml version="1.0"?><rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel>
        <title>Nodesignal</title><podcast:guid>$guid</podcast:guid>
        <podcast:txt purpose="sovereigncast">$marker</podcast:txt>
        <item><title>Ep</title><guid>e</guid><enclosure url="https://cdn.test/e.mp3" type="audio/mpeg"/></item>
        </channel></rss>"""

    private val http = HttpClient(MockEngine { req ->
        (req.body as? TextContent)?.let { bodies += it.text }
        val path = req.url.encodedPath
        when {
            req.url.host == "pod.test" -> respond(feed())
            instanceDown -> throw java.io.IOException("instance down")
            path == "/api/listener/meta" -> respond("""{"api_versions":["v1"],"instance":{"id":"i","software":"sovereigncast","version":"dev"}}""")
            path == "/api/listener/v1/shows/by-guid/$guid" -> if (!showKnown) respondError(HttpStatusCode.NotFound, """{"error":{"code":"show_not_found","message":"no"}}""")
                else respond("""{"podcast_guid":"$guid","title":"Nodesignal","feed_url":"$reportedFeed","community_page_url":"https://pm.test/community",
                    "capabilities":{"release_boosting":true,"boost2unlock":false,"halving_clip":true,"suggestions":true,"leaderboard":true,"top_episodes":true,"payment_metadata":true},
                    "amounts":{"suggestion_min_sats":21},"poll_after_seconds":3600}""")
            path.endsWith("/release-boosting") -> respond("""{"round":{"episode":{"episode_guid":"e297","episode_nr":297,"title":"E297","description":"","image_url":null},
                "goal_sats":15120,"raised_sats":210,"sats_per_minute":21,"publish_at":"2026-10-09T21:50:00Z","chain":{"block_sats":210,"blocks":72,"mined_blocks":1,"halving_block":36},"clip":{"unlocked":false,"at_block":36}},"poll_after_seconds":60}""")
            path.endsWith("/leaderboard") -> respond(LEADERBOARD)
            // Phase 2: a new suggestion and a suggestion's boost are invoices like the others.
            req.method == io.ktor.http.HttpMethod.Post && (path.endsWith("/suggestions") || path.endsWith("/suggestions/32/invoices")) -> respond(
                """{"payment_hash":"$hashInJson","bolt11":"${TestInvoices.make(invoiceAmountMsat ?: 210_000, ByteArray(32))}","amount_sats":210,"expires_at":"2026-10-09T22:00:00Z"}""",
                HttpStatusCode.Created)
            path.endsWith("/top-episodes") -> respond("""{"episodes":[{"rank":1,"episode_guid":"e","episode_nr":1,"title":"Ep","description":"Die beste.","website_url":null}]}""")
            path.endsWith("/suggestions") -> respond("""{"suggestions":[{"id":32,"title":"BIP110","description":"","username":"Anonymous","amount_sats":2226,"vote_count":12,"created_at":"2026-07-06T11:39:40Z"}]}""")
            path.endsWith("/votes") -> respond("""{"already_voted":false}""")
            path.endsWith("/release-boosting/invoices") -> respond(
                """{"payment_hash":"$hashInJson","bolt11":"${TestInvoices.make(invoiceAmountMsat ?: 210_000, ByteArray(32))}","amount_sats":210,"expires_at":"2026-10-09T22:00:00Z"}""",
                HttpStatusCode.Created)
            path == "/api/listener/v1/payments/${TestInvoices.PAYMENT_HASH}" -> respond("""{"payment_hash":"${TestInvoices.PAYMENT_HASH}","status":"${paymentStatus ?: if (++statusCalls < 2) "pending" else "paid"}","poll_after_seconds":1}""")
            else -> respondError(HttpStatusCode.NotFound)
        }
    })
    private var now = 1_780_000_000L
    private val db = HoppDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HoppDatabase.Schema.create(it) })
    private val settings = SettingsRepository(db)
    private val podcasts = PodcastRepository(db, FeedFetcher(http), { now })
    private val episodes = EpisodeRepository(db, http, { now })
    private val relay = FakeRelay()
    private val fake by lazy { FakeWallet(relay).also { it.publishInfo() } }
    private val wallet = WalletRepository(MemorySecretStore(), http, { now }, relay, timeout = Duration.parse("5s"))
    private val value = ValueService(db, wallet, episodes, podcasts, settings, LnAddressClient(http), FeedFetcher(http), null, { now }, AppInfo("0.0.1", "JVM", "Test"))
    private val sc = SovereigncastRepository(db, ListenerApi(http), podcasts, settings, wallet, value, { now })

    private fun test(block: suspend () -> Unit) { runBlocking { withTimeout(30_000) { block() } } }
    private suspend fun subscribe() = (podcasts.subscribe("https://pod.test/feed.xml") as SubscribeResult.Subscribed).podcastId

    @Test
    fun theInstanceConfirmsTheShowBeforeTheLayerTurnsOn() = test {
        val id = subscribe()
        assertNull(sc.layer(id).first())
        sc.verify(id)
        val layer = assertNotNull(sc.layer(id).first())
        assertEquals("pm.test", layer.host)
        assertTrue(layer.show.capabilities.releaseBoosting && layer.show.capabilities.suggestions)
        assertEquals(21, layer.show.amounts.suggestionMinSats)
    }

    @Test
    fun aFeedPointingAtAForeignInstanceGetsNoLayer() = test {
        reportedFeed = "https://other.test/feed.xml" // the instance manages the GUID, but for another feed
        val id = subscribe()
        sc.verify(id)
        assertNull(sc.layer(id).first())
    }

    @Test
    fun onlyHttpsMarkersCount() = test {
        marker = "http://pm.test"
        val id = subscribe()
        sc.verify(id)
        assertNull(sc.layer(id).first())
    }

    @Test
    fun anInstanceThatIsDownKeepsTheLayerOneThatSaysNoEndsIt() = test {
        val id = subscribe()
        sc.verify(id)
        instanceDown = true
        sc.verify(id, force = true)
        assertNotNull(sc.layer(id).first(), "offline is not a no")
        instanceDown = false
        showKnown = false
        sc.verify(id, force = true)
        assertNull(sc.layer(id).first())
    }

    @Test
    fun releaseBoostingAndSuggestionsAreRead() = test {
        val id = subscribe(); sc.verify(id)
        val layer = sc.layer(id).first()!!
        val round = sc.releaseBoosting(layer).round!!
        assertEquals(210L to 15120L, round.raisedSats to round.goalSats)
        assertEquals(21, round.satsPerMinute)
        assertEquals("BIP110", sc.suggestions(layer).suggestions.single().title)
    }

    @Test
    fun theLeaderboardAndTheClipMarkAreRead() = test {
        val id = subscribe(); sc.verify(id)
        val layer = sc.layer(id).first()!!
        assertEquals(ClipState(unlocked = false, atBlock = 36), sc.releaseBoosting(layer).round!!.clip)
        val board = sc.leaderboard(layer)
        assertEquals("2026-10", board.month)
        val top = board.topBoosts.single()
        assertEquals(Triple(1, 21000L, "E296 - Keine Node ist auch keine Lösung"), Triple(top.rank, top.amountSats, top.episode.title))
        assertNull(board.recentBoosts.single().episode.episodeGuid, "a boost to the whole show")
        val first = board.alltimeBoosters.single()
        assertTrue(first.legendary && first.medals.gold == 4 && first.trend == "same")
    }

    @Test
    fun aVoteIsAnonymousOnceAndRemembered() = test {
        val id = subscribe(); sc.verify(id)
        val layer = sc.layer(id).first()!!
        sc.vote(layer, 32)
        val voter = settings.current().voterId
        assertEquals(32, voter.length)
        assertTrue(bodies.any { "\"voter_id\":\"$voter\"" in it })
        assertTrue(sc.voted(layer, 32, settings.current().votes))
        sc.vote(layer, 32)
        assertEquals(voter, settings.current().voterId, "one id, made once")
    }

    @Test
    fun anInvoiceForAnotherAmountIsNeverPaid() = test {
        val id = subscribe(); sc.verify(id)
        val layer = sc.layer(id).first()!!
        invoiceAmountMsat = 2_100_000 // the instance asks ten times as much
        assertFailsWith<InvoiceMismatch> { sc.invoice(layer, ScTarget.ReleaseBoosting, 210, null, null) }
    }

    /** Security review 2026-10-10, M-06: the hash Hopp waits on must be the invoice's own —
     *  another, already paid one would report a payment that never happened. */
    @Test
    fun anInvoiceWithAnotherHashIsRefused() = test {
        val id = subscribe(); sc.verify(id)
        val layer = sc.layer(id).first()!!
        hashInJson = "ff".repeat(32)
        assertFailsWith<InvoiceMismatch> { sc.invoice(layer, ScTarget.ReleaseBoosting, 210, null, null) }
    }

    @Test
    fun releaseBoostingIsPaidConfirmedAndLogged() = test {
        val id = subscribe(); sc.verify(id)
        val layer = sc.layer(id).first()!!
        wallet.connect("nostr+walletconnect://${fake.keys.publicKeyHex}?relay=wss://r.test&secret=${KeyPair.generate().secretKey.toHex()}")
        val invoice = sc.invoice(layer, ScTarget.ReleaseBoosting, 210, "Schneller!", "Satoshi")
        assertTrue(bodies.any { "\"amount_sats\":210" in it && "\"message\":\"Schneller!\"" in it })
        val fees = sc.payWithWallet(invoice)
        assertEquals("pay_invoice", fake.requests.last().first)
        assertTrue(sc.awaitPaid(layer, invoice))
        sc.log(id, layer, ScTarget.ReleaseBoosting, "E297", 210, "Schneller!", fees)
        val logged = value.payments.first().single()
        assertEquals(ValueService.KIND_RELEASE_BOOSTING, logged.kind)
        assertEquals(210_000L, logged.amountMsat)
    }

    /** Phase 2: a new suggestion carries its topic and the listener's name, and is
     *  logged as one once the instance confirmed it. */
    @Test
    fun aSuggestionIsSubmittedPaidAndLogged() = test {
        val id = subscribe(); sc.verify(id)
        val layer = sc.layer(id).first()!!
        wallet.connect("nostr+walletconnect://${fake.keys.publicKeyHex}?relay=wss://r.test&secret=${KeyPair.generate().secretKey.toHex()}")
        val target = ScTarget.NewSuggestion(" Ark und Spark ", "Wie sie sich unterscheiden", "https://ark.test")
        val invoice = sc.invoice(layer, target, 210, null, "Satoshi")
        val sent = bodies.last()
        assertTrue("\"title\":\"Ark und Spark\"" in sent && "\"links\":\"https://ark.test\"" in sent && "\"username\":\"Satoshi\"" in sent && "\"amount_sats\":210" in sent, sent)
        val fees = sc.payWithWallet(invoice)
        assertTrue(sc.awaitPaid(layer, invoice))
        sc.log(id, layer, target, "Ark und Spark", 210, null, fees)
        assertEquals(ValueService.KIND_SUGGESTION, value.payments.first().single().kind)
    }

    /** Below the show's minimum the instance raises the amount — an invoice Hopp
     *  then refuses, as any that asks for something else (ADR 0005). */
    @Test
    fun aRaisedSuggestionAmountIsNeverPaid() = test {
        val id = subscribe(); sc.verify(id)
        val layer = sc.layer(id).first()!!
        invoiceAmountMsat = 21_000
        assertFailsWith<InvoiceMismatch> { sc.invoice(layer, ScTarget.NewSuggestion("Thema"), 10, null, null) }
    }

    @Test
    fun aSuggestionIsBoostedAndTheTopEpisodesAreRead() = test {
        val id = subscribe(); sc.verify(id)
        val layer = sc.layer(id).first()!!
        sc.invoice(layer, ScTarget.SuggestionBoost(32), 210, "Bitte!", null)
        assertTrue(bodies.last().let { "\"amount_sats\":210" in it && "\"message\":\"Bitte!\"" in it })
        assertEquals("Die beste.", sc.topEpisodes(layer).episodes.single().description)
    }

    /** The amount that publishes or unlocks at once, as the community page computes it
     *  (community.js, renderBoosting) — a preset in Hopp too (maintainer, 2026-10-16). */
    @Test
    fun theFillAmountFollowsTheCommunityPage() {
        val publish = kotlin.time.Instant.parse("2026-10-09T21:50:00Z").epochSeconds
        val round = ReleaseBoostingRound(UpcomingEpisode("e297", 297, "E297"), goalSats = 15120, raisedSats = 210, satsPerMinute = 21, publishAt = "2026-10-09T21:50:00Z")
        // Long before the earliest release (710 minutes before the date): the rest of the goal.
        assertEquals(ScFill(14910, publishesNow = false), releaseFill(round, publish - 1000 * 60))
        // Past it: up to now — whole minutes over the whole balance, one minute of margin.
        assertEquals(ScFill(2121, publishesNow = true), releaseFill(round, publish - 100 * 60))
        assertEquals(ScFill(2121, publishesNow = true), releaseFill(round, publish - 100 * 60 + 30), "part of a minute counts as one")
        assertEquals(ScFill(21 * 2 + 21 * 10 - 210 + 21, publishesNow = true), releaseFill(round, publish - 2 * 60), "close to the date: a little")
        assertNull(releaseFill(round.copy(raisedSats = 15120), publish - 100 * 60), "a full round needs nothing")
        // Boost2Unlock: the rest of the goal, until unlocked.
        val b2u = Boost2UnlockEpisode(UpcomingEpisode("e1", 1, "E1"), goalSats = 1000, raisedSats = 400, unlocked = false)
        assertEquals(ScFill(600, publishesNow = true), unlockFill(b2u))
        assertNull(unlockFill(b2u.copy(unlocked = true)))
    }

    /** M3: after the wallet's answer is missing, a second tap does not pay that invoice again. */
    @Test
    fun anUnknownInvoiceIsLoggedAndNotPaidAgain() = onMain {
        val id = subscribe()
        wallet.connect("nostr+walletconnect://${fake.keys.publicKeyHex}?relay=wss://r.test&secret=${KeyPair.generate().secretKey.toHex()}")
        val vm = CommunityViewModel(id, sc, settings, wallet, episodes)
        withTimeout(5_000) { while (vm.layer.value == null || !vm.hasWallet.value) delay(10) }
        fake.silentMethod = "pay_invoice"
        vm.pay(ScTarget.ReleaseBoosting, 210, "", "Satoshi", "E297")
        withTimeout(15_000) { while (vm.pay.value !is ScPay.Failed) delay(20) }
        assertEquals("unknown", (vm.pay.value as ScPay.Failed).code)
        assertEquals(1, fake.requests.count { it.first == "pay_invoice" })
        val payment = value.payments.first().single()
        assertEquals("unknown", payment.lines.single().status)
        assertEquals(0L, payment.amountMsat, "not counted as sent")
        // It may have left the wallet: against the budget (security audit 2026-10-18, M-03).
        assertEquals(210_000L, db.paymentQueries.spentSince(0).executeAsOne())
        vm.resetPay()
        vm.pay(ScTarget.ReleaseBoosting, 210, "", "Satoshi", "E297")
        delay(200)
        assertEquals(1, fake.requests.count { it.first == "pay_invoice" }, "the same invoice was paid again")
        assertEquals("unknown", (vm.pay.value as ScPay.Failed).code)
    }

    /** M3, code review 2026-10-17: the wallet paid, the instance did not confirm in
     *  time — sent and counted, with the reason in the history; that invoice is not
     *  sent to the wallet again. */
    @Test
    fun aPaymentTheWalletMadeCountsEvenUnconfirmed() = onMain {
        paymentStatus = "expired"
        val id = subscribe()
        wallet.connect("nostr+walletconnect://${fake.keys.publicKeyHex}?relay=wss://r.test&secret=${KeyPair.generate().secretKey.toHex()}")
        val vm = CommunityViewModel(id, sc, settings, wallet, episodes)
        withTimeout(5_000) { while (vm.layer.value == null || !vm.hasWallet.value) delay(10) }
        vm.pay(ScTarget.ReleaseBoosting, 210, "", "Satoshi", "E297")
        withTimeout(10_000) { while (vm.pay.value !is ScPay.Done) delay(20) }
        val payment = value.payments.first().single()
        assertEquals("paid" to CommunityViewModel.UNCONFIRMED, payment.lines.single().let { it.status to it.reason })
        assertEquals(211_000L, db.paymentQueries.spentSince(0).executeAsOne(), "counted against the budget: 210 sats and the wallet's fee")
        vm.resetPay()
        vm.pay(ScTarget.ReleaseBoosting, 210, "", "Satoshi", "E297")
        withTimeout(5_000) { while (vm.pay.value !is ScPay.Failed) delay(20) }
        assertEquals(1, fake.requests.count { it.first == "pay_invoice" }, "the same invoice was paid again")
    }

    /** Code review 2026-10-17: without a wallet the listener pays in another app;
     *  an invoice that expires there was not paid — nothing in the history, and a
     *  new one can be asked for. */
    @Test
    fun anExternalInvoiceThatExpiresIsNoPayment() = onMain {
        paymentStatus = "expired"
        val id = subscribe()
        val vm = CommunityViewModel(id, sc, settings, wallet, episodes)
        withTimeout(5_000) { while (vm.layer.value == null) delay(10) }
        repeat(2) {
            vm.resetPay()
            vm.pay(ScTarget.ReleaseBoosting, 210, "", "Satoshi", "E297")
            withTimeout(10_000) { while (vm.pay.value !is ScPay.Failed) delay(20) }
            assertEquals("expired", (vm.pay.value as ScPay.Failed).code)
        }
        assertEquals(2, bodies.count { "\"amount_sats\":210" in it }, "the second tap asked for a new invoice")
        assertTrue(value.payments.first().isEmpty(), "an invoice nobody paid is no payment")
    }

    /** ADR 0009: the App Store binary does not pay the instance at all. */
    @Test
    fun theAppStoreBinaryAsksForNoInvoice() = onMain {
        val id = subscribe()
        val vm = CommunityViewModel(id, sc, settings, wallet, episodes, paysForContent = false)
        withTimeout(5_000) { while (vm.layer.value == null) delay(10) }
        vm.pay(ScTarget.ReleaseBoosting, 210, "", "Satoshi", "E297")
        delay(200)
        assertEquals(ScPay.Idle, vm.pay.value)
        assertTrue(bodies.none { "\"amount_sats\"" in it }, "an invoice was asked for")
    }

    private fun onMain(block: suspend () -> Unit) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            test(block)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
