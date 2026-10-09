package space.sovereigncast.hopp.sovereigncast

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

/** The slim Sovereigncast layer of phase 1 (ADR 0002, 0010, 0018). */
class SovereigncastTest {
    private val guid = "e8df0b13-47de-544a-99b7-ec7cbd960a16"
    private var marker = "https://pm.test"
    private var reportedFeed = "https://pod.test/feed.xml"
    private var instanceDown = false
    private var showKnown = true
    private var invoiceAmountMsat: Long? = null
    private var statusCalls = 0
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
                "goal_sats":15120,"raised_sats":210,"sats_per_minute":21,"publish_at":"2026-10-09T21:50:00Z","chain":{"block_sats":210,"blocks":72,"mined_blocks":1,"halving_block":36},"clip":null},"poll_after_seconds":60}""")
            path.endsWith("/suggestions") -> respond("""{"suggestions":[{"id":32,"title":"BIP110","description":"","username":"Anonymous","amount_sats":2226,"vote_count":12,"created_at":"2026-07-06T11:39:40Z"}]}""")
            path.endsWith("/votes") -> respond("""{"already_voted":false}""")
            path.endsWith("/release-boosting/invoices") -> respond(
                """{"payment_hash":"h1","bolt11":"${TestInvoices.make(invoiceAmountMsat ?: 210_000, ByteArray(32))}","amount_sats":210,"expires_at":"2026-10-09T22:00:00Z"}""",
                HttpStatusCode.Created)
            path == "/api/listener/v1/payments/h1" -> respond("""{"payment_hash":"h1","status":"${if (++statusCalls < 2) "pending" else "paid"}","poll_after_seconds":1}""")
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
}
