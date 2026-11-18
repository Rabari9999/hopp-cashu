package space.sovereigncast.hopp.value.cashu

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.net.createHttpClient
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.value.Bolt11
import space.sovereigncast.hopp.value.ProvenWallet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wallet against a real mint (cdk's testnut, a FakeWallet behind it: its
 * invoices are paid on their own). Off unless HOPP_LIVE_MINT is set — CI never
 * goes to the network:
 *
 *     HOPP_LIVE_MINT=https://testnut.cashu.space ./gradlew :shared:jvmTest --tests '*LiveMintTest'
 */
class LiveMintTest {
    private val mintUrl = System.getenv("HOPP_LIVE_MINT")

    @Test
    fun mintSendReceiveAndMeltAtARealMint() = runBlocking {
        if (mintUrl.isNullOrBlank()) return@runBlocking
        val http = createHttpClient(AppInfo("0.0.1", "JVM", "Test"))
        val store = ProofStore(MemorySecretStore())
        CashuWallet.create(store, mintUrl)
        val wallet = CashuWallet(store, http) { System.currentTimeMillis() / 1000 }

        wallet.requestInvoice(100)
        repeat(20) { if (wallet.balanceMsat() < 100_000) { delay(500); wallet.refresh() } }
        assertEquals(100_000, wallet.balanceMsat(), "minted")
        println("LIVE minted 100, proofs ${store.load()!!.proofs.map { it.amount }}, keyset ${store.load()!!.proofs.first().id}")

        val token = wallet.send(10)
        val afterSend = wallet.balanceMsat() / 1000
        println("LIVE sent 10 as ${token.take(24)}…, left $afterSend")
        assertTrue(afterSend in 80..90, "sending 10 cost the token and the input fee: $afterSend")

        val received = wallet.receive(token)
        println("LIVE received back $received")
        assertTrue(received in 8..10)

        // The mint's own invoice as the one to pay: its FakeWallet pays anything.
        val invoice = MintClient(http, mintUrl).mintQuote(5).request
        val before = wallet.balanceMsat() / 1000
        val paid = wallet.payInvoice(invoice, null)
        val after = wallet.balanceMsat() / 1000
        val hash = Bolt11.parse(invoice).paymentHash
        val proven = paid.preimage?.let { Sha256.digest(it.hexToBytes()).toHex() == hash } ?: false
        println("LIVE melted 5: balance $before → $after, fees ${paid.feesPaidMsat} msat, preimage ${paid.preimage}, matches hash: $proven")
        assertTrue(after in (before - 5 - 4)..(before - 5), "paid 5 plus a small fee")

        // What Hopp's own payment path makes of it.
        val viaHopp = runCatching { ProvenWallet(wallet).payInvoice(MintClient(http, mintUrl).mintQuote(3).request, null) }
        println("LIVE via ProvenWallet: ${viaHopp.fold({ "paid, preimage ${it.preimage}" }, { "${it::class.simpleName}: ${it.message}" })}")
        println("LIVE end balance ${wallet.balanceMsat() / 1000}, pending ${store.load()!!.pending.size}")
        assertTrue(store.load()!!.pending.isEmpty(), "nothing left in flight")
    }
}
