package space.sovereigncast.hopp.value

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.nostr.KeyPair
import space.sovereigncast.hopp.nostr.RelayConnector
import space.sovereigncast.hopp.nostr.RelaySession
import space.sovereigncast.hopp.value.nwc.NwcClient
import space.sovereigncast.hopp.value.nwc.NwcConnection
import space.sovereigncast.hopp.value.nwc.NwcException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** NIP-47 end to end against an in-memory relay and a fake wallet service —
 *  real signatures, real NIP-44. */
class NwcClientTest {
    private val relay = FakeRelay()
    private val wallet = FakeWallet(relay)
    private val clientKeys = KeyPair.generate()
    private val uri = "nostr+walletconnect://${wallet.keys.publicKeyHex}?relay=wss%3A%2F%2Frelay.example&secret=${clientKeys.secretKey.toHex()}&lud16=hopp%40example.test"
    private val client = NwcClient(NwcConnection.parse(uri), relay, now = { 1_780_000_000 }, timeout = 5.seconds)

    @Test
    fun theUriIsParsed() {
        val c = NwcConnection.parse(uri)
        assertEquals(listOf("wss://relay.example"), c.relays)
        assertEquals("hopp@example.test", c.lud16)
        assertEquals(clientKeys.publicKeyHex, c.clientKeys.publicKeyHex)
        assertFailsWith<IllegalArgumentException> { NwcConnection.parse("https://example.test") }
    }

    @Test
    fun walletInfoComesFromTheInfoEvent() = runBlocking {
        wallet.publishInfo()
        val info = client.info()
        assertTrue(info.canKeysend && info.canPayInvoice)
        assertTrue("nip44_v2" in info.encryptions)
    }

    @Test
    fun aKeysendBoostCarriesItsTlvRecords() = runBlocking {
        val boost = Boostagram(
            action = "boost", appName = "Hopp", appVersion = "0.0.1", podcast = "Nodesignal", episode = "Folge 296",
            feedUrl = "https://serve.podhome.fm/rss/e8df0b13", podcastGuid = "e8df0b13-47de-544a-99b7-ec7cbd960a16",
            episodeGuid = "df6d311f", positionSeconds = 1634, valueMsat = 1_680_000, valueMsatTotal = 2_100_000,
            recipientName = "Nodesignal", senderName = "Satoshi", message = "Danke!",
        )
        val result = client.payKeysend(1_680_000, "02d0fd6c", keysendRecords(boost, "696969", "2"))
        assertEquals("00".repeat(32), result.preimage)
        val (method, params) = wallet.requests.single()
        assertEquals("pay_keysend", method)
        assertEquals(1_680_000, params["amount"]!!.jsonPrimitive.long)
        val types = params["tlv_records"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.long }
        assertEquals(listOf(7_629_169L, 696_969L), types)
    }

    @Test
    fun aWalletErrorReachesTheCaller() = runBlocking {
        wallet.failWith = "INSUFFICIENT_BALANCE" to "not enough sats"
        val e = assertFailsWith<NwcException> { client.payInvoice("lnbc1...") }
        assertEquals("INSUFFICIENT_BALANCE", e.code)
    }

    @Test
    fun aRelayRejectionIsReported() = runBlocking {
        relay.rejectEvents = true
        val e = assertFailsWith<NwcException> { client.balanceMsat() }
        assertEquals("RELAY_REJECTED", e.code)
    }

    @Test
    fun theBalanceIsRead() = runBlocking {
        assertEquals(21_000_000, client.balanceMsat())
    }

    /** The relay closes the connection right after the request went out (code
     *  review 2026-10-17, H1): the wallet may have paid — never a plain failure
     *  that the LNURL fallback or the next streaming batch would pay again over. */
    @Test
    fun aConnectionLostAfterSendingIsAnUnknownOutcome() = runBlocking {
        wallet.silentMethod = "pay_keysend"
        val dropping = RelayConnector { url ->
            val session = relay.connect(url) as FakeRelay.Session
            object : RelaySession by session {
                override suspend fun send(message: String) {
                    session.send(message)
                    if (message.startsWith("[\"EVENT\"")) session.channel.close()
                }
            }
        }
        val c = NwcClient(NwcConnection.parse(uri), dropping, now = { 1_780_000_000 }, timeout = 5.seconds)
        val e = assertFailsWith<NwcException> { c.payKeysend(1_000, "02d0fd6c", emptyList()) }
        assertTrue(e.outcomeUnknown, e.toString())
    }

    /** Cancelled after the request went out (M1): the same unknown outcome as a
     *  dropped connection, so the caller does not pay the share again. */
    @Test
    fun aCancellationAfterSendingIsAnUnknownOutcome() = runBlocking {
        wallet.silentMethod = "pay_keysend"
        val c = NwcClient(NwcConnection.parse(uri), relay, now = { 1_780_000_000 }, timeout = 5.seconds)
        var thrown: Throwable? = null
        val job = launch {
            try {
                c.payKeysend(1_000, "02d0fd6c", emptyList())
            } catch (t: Throwable) {
                thrown = t
            }
        }
        withTimeout(5.seconds) { while (wallet.requests.none { it.first == "pay_keysend" }) yield() }
        job.cancel()
        job.join()
        val e = thrown
        assertTrue(e is NwcException && e.outcomeUnknown, "was ${e?.let { it::class.simpleName + ": " + it.message }}")
    }
}
