package space.sovereigncast.hopp.value

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.model.ValueBlock
import space.sovereigncast.hopp.model.ValueRecipient
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.value.nwc.NwcClient
import space.sovereigncast.hopp.value.nwc.NwcConnection
import space.sovereigncast.hopp.value.nwc.WalletInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class BoostSenderTest {
    private val relay = FakeRelay()
    private val wallet = FakeWallet(relay)
    private val nwc = NwcClient(
        NwcConnection.parse("nostr+walletconnect://${wallet.keys.publicKeyHex}?relay=wss://r&secret=${KeyPair.generate().secretKey.toHex()}"),
        relay, now = { 1 }, timeout = 5.seconds,
    )
    private val metadata = """[["text/plain","x"]]"""
    private val http = HttpClient(MockEngine { req ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when (req.url.encodedPath) {
            "/.well-known/keysend/withroute" -> respond("""{"status":"OK","tag":"keysend","pubkey":"03route","customData":[{"customKey":"696969","customValue":"7"}]}""", headers = json)
            "/.well-known/lnurlp/noroute", "/.well-known/lnurlp/withroute" -> respond("""{"tag":"payRequest","callback":"https://w.test/cb","minSendable":1000,"maxSendable":1000000000,"metadata":${JsonPrimitive(metadata)},"commentAllowed":100}""", headers = json)
            "/cb" -> respond("""{"pr":"${TestInvoices.make(req.url.parameters["amount"]!!.toLong(), Sha256.digest(metadata.encodeToByteArray()))}"}""", headers = json)
            else -> respond("", HttpStatusCode.NotFound)
        }
    })
    private val ctx = BoostContext("boost", "0.0.1", "Show", "Ep", null, "guid", "ep-guid", 60, "Satoshi", "Danke!",
        rssPaymentUrl = "https://pm.example/p/abc")

    private fun r(name: String, type: String, address: String, split: Int) = ValueRecipient(name, type, address, split)

    @Test
    fun eachRecipientTakesItsRoute() = runBlocking {
        val block = ValueBlock("lightning", "keysend", recipients = listOf(
            r("node", "node", "02node", 50),
            r("route", "lnaddress", "withroute@w.test", 30),
            r("lnurl", "lnaddress", "noroute@w.test", 20),
        ))
        val sender = BoostSender(nwc, LnAddressClient(http), WalletInfo(setOf("pay_keysend", "pay_invoice"), setOf("nip44_v2")))
        val results = sender.send(block, 10_000, ctx)
        println(results.describe())
        assertEquals(listOf("keysend", "lnaddress keysend", "lnurl"), results.map { (it as ShareResult.Paid).route })
        assertEquals(listOf("pay_keysend", "pay_keysend", "pay_invoice"), wallet.requests.map { it.first })
        // The keysend route's own pubkey and custom record are used.
        assertEquals("03route", wallet.requests[1].second["pubkey"]!!.jsonPrimitive.content)
    }

    @Test
    fun withoutKeysendTheNodeIsSkippedAndTheAddressGoesByLnurl() = runBlocking {
        val block = ValueBlock("lightning", "keysend", recipients = listOf(
            r("node", "node", "02node", 50),
            r("route", "lnaddress", "withroute@w.test", 50),
        ))
        // Phoenix-like wallet: invoices only — the keysend route is not even tried.
        val sender = BoostSender(nwc, LnAddressClient(http), WalletInfo(setOf("pay_invoice"), setOf("nip44_v2")))
        val results = sender.send(block, 10_000, ctx)
        println(results.describe())
        assertIs<ShareResult.Skipped>(results[0])
        assertEquals("lnurl", (results[1] as ShareResult.Paid).route)
        Unit
    }

    /** Device feedback 2026-10-08: an address whose keysend route fails is
     *  paid by invoice after all, instead of failing. */
    @Test
    fun aFailedKeysendRouteFallsBackToAnInvoice() = runBlocking {
        val block = ValueBlock("lightning", "keysend", recipients = listOf(r("Cutting", "lnaddress", "withroute@w.test", 100)))
        wallet.failMethod = "pay_keysend"
        val sender = BoostSender(nwc, LnAddressClient(http), WalletInfo(setOf("pay_keysend", "pay_invoice"), setOf("nip44_v2")))
        val result = sender.send(block, 10_000, ctx).single()
        println(listOf(result).describe())
        assertIs<ShareResult.Paid>(result)
        assertEquals("lnurl after keysend failed", result.route)
        assertEquals(listOf("pay_keysend", "pay_invoice"), wallet.requests.map { it.first })
    }

    /** The paying wallet's own address or node is not paid — it would be a
     *  payment to oneself, which wallets refuse. */
    @Test
    fun theWalletDoesNotPayItself() = runBlocking {
        val block = ValueBlock("lightning", "keysend", recipients = listOf(
            r("Me", "lnaddress", "Noroute@W.test", 30),
            r("My node", "node", "02mine", 30),
            r("Other", "node", "02other", 40),
        ))
        val sender = BoostSender(nwc, LnAddressClient(http),
            WalletInfo(setOf("pay_keysend", "pay_invoice"), setOf("nip44_v2"), ownPubkey = "02mine", ownAddress = "noroute@w.test"))
        val results = sender.send(block, 10_000, ctx)
        println(results.describe())
        assertEquals(listOf(SkipReason.SELF, SkipReason.SELF), results.filterIsInstance<ShareResult.Skipped>().map { it.reason })
        assertEquals(listOf("pay_keysend"), wallet.requests.map { it.first }, "only the other node is paid")
        assertEquals("self", unpayableReason(block.recipients[0], WalletInfo(setOf("pay_invoice"), emptySet(), ownAddress = "noroute@w.test")))
    }
}
