package space.sovereigncast.hopp.value.cashu

import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.data.Clock
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.value.nwc.NwcException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CashuWallet against a mint that signs for real and misbehaves on request. */
class CashuWalletTest {
    private val clock = Clock { 1_800_000_000 }

    private fun wallet(mint: FakeMint, store: ProofStore = ProofStore(MemorySecretStore())): Pair<CashuWallet, ProofStore> {
        CashuWallet.create(store, "https://mint.test/")
        return CashuWallet(store, mint.http, clock) to store
    }

    private suspend fun CashuWallet.funded(mint: FakeMint, sat: Long) = apply {
        requestInvoice(sat)
        mint.payInvoices()
        refresh()
    }

    @Test
    fun anInvoicePaidBecomesEcash() = runBlocking {
        val mint = FakeMint()
        val (w, store) = wallet(mint)
        val invoice = w.requestInvoice(64)
        assertTrue(invoice.startsWith("lnbcfake"))
        assertEquals(0, w.balanceMsat())
        mint.payInvoices()
        assertEquals(64_000, w.balanceMsat())
        assertTrue(store.load()!!.pending.isEmpty())
        assertTrue(store.load()!!.proofs.all(mint.keyset::accepts))
    }

    @Test
    fun payingAnInvoiceMeltsAndTakesChangeBack() = runBlocking {
        val mint = FakeMint()
        val (w, store) = wallet(mint)
        w.funded(mint, 64)
        val paid = w.payInvoice("lnbc1invoice")
        assertEquals(mint.preimage, paid.preimage)
        assertEquals(1_000, paid.feesPaidMsat) // the Lightning fee; the rest of the reserve came back
        assertEquals((64 - 20 - 1) * 1000L, w.balanceMsat())
        assertTrue(store.load()!!.pending.isEmpty())
    }

    @Test
    fun inputFeesAreChargedAndCounted() = runBlocking {
        val mint = FakeMint(feePpk = 1000)
        val (w, _) = wallet(mint)
        w.funded(mint, 64)
        val paid = w.payInvoice("lnbc1invoice")
        assertEquals(2_000, paid.feesPaidMsat) // 1 Lightning + 1 for the one input
        assertEquals((64 - 20 - 2) * 1000L, w.balanceMsat())
    }

    @Test
    fun notEnoughIsRefusedBeforeAnythingIsSpent() = runBlocking {
        val mint = FakeMint().apply { meltAmount = 100 }
        val (w, _) = wallet(mint)
        w.funded(mint, 64)
        val e = assertFailsWith<NwcException> { w.payInvoice("lnbc1invoice") }
        assertEquals("INSUFFICIENT_BALANCE", e.code)
        assertFalse(mint.calls.any { it.endsWith("/v1/melt/bolt11") })
        assertEquals(64_000, w.balanceMsat())
    }

    @Test
    fun aRefusedMeltIsAFailureAndTheProofsComeBack() = runBlocking {
        val mint = FakeMint().apply { refuseMelt = true }
        val (w, _) = wallet(mint)
        w.funded(mint, 64)
        val e = assertFailsWith<NwcException> { w.payInvoice("lnbc1invoice") }
        assertEquals("PAYMENT_FAILED", e.code)
        assertFalse(e.outcomeUnknown)
        assertEquals(64_000, w.balanceMsat())
    }

    @Test
    fun aFailedLightningPaymentReturnsTheProofs() = runBlocking {
        val mint = FakeMint().apply { meltOutcome = "UNPAID" }
        val (w, _) = wallet(mint)
        w.funded(mint, 64)
        assertEquals("PAYMENT_FAILED", assertFailsWith<NwcException> { w.payInvoice("lnbc1invoice") }.code)
        assertEquals(64_000, w.balanceMsat())
    }

    @Test
    fun aLostAnswerIsUnknownThenSettledAsPaid() = runBlocking {
        val mint = FakeMint().apply { dropAfterMelt = true }
        val (w, store) = wallet(mint)
        w.funded(mint, 64)
        assertTrue(assertFailsWith<NwcException> { w.payInvoice("lnbc1invoice") }.outcomeUnknown)
        assertEquals(1, store.load()!!.pending.size)
        assertEquals((64 - 20 - 1) * 1000L, w.balanceMsat()) // the quote says PAID: its change is taken in
        assertTrue(store.load()!!.pending.isEmpty())
    }

    @Test
    fun aLostAnswerOnAnUnpaidMeltGivesTheProofsBack() = runBlocking {
        val mint = FakeMint().apply { dropAfterMelt = true; meltOutcome = "UNPAID" }
        val (w, _) = wallet(mint)
        w.funded(mint, 64)
        assertTrue(assertFailsWith<NwcException> { w.payInvoice("lnbc1invoice") }.outcomeUnknown)
        assertEquals(64_000, w.balanceMsat())
    }

    @Test
    fun aPendingMeltStaysOutOfTheBalanceUntilItSettles() = runBlocking {
        val mint = FakeMint().apply { meltOutcome = "PENDING" }
        val (w, store) = wallet(mint)
        w.funded(mint, 64)
        assertTrue(assertFailsWith<NwcException> { w.payInvoice("lnbc1invoice") }.outcomeUnknown)
        assertEquals(0, w.balanceMsat())
        assertEquals(1, store.load()!!.pending.size)
        mint.settlePendingMelts()
        assertEquals((64 - 20 - 1) * 1000L, w.balanceMsat())
    }

    @Test
    fun sendAndReceiveATokenOnce() = runBlocking {
        val mint = FakeMint()
        val (alice, _) = wallet(mint)
        val (bob, _) = wallet(mint)
        alice.funded(mint, 64)
        val token = alice.send(10, "thanks")
        assertTrue(token.startsWith("cashuB"))
        assertEquals(10, CashuTokens.decode(token).amount)
        assertEquals(54_000, alice.balanceMsat())
        assertEquals(10, bob.receive(token))
        assertEquals(10_000, bob.balanceMsat())
        assertFailsWith<CashuMintException> { bob.receive(token) }
        assertEquals(10_000, bob.balanceMsat())
    }

    @Test
    fun aSwapWhoseAnswerIsLostIsRestored() = runBlocking {
        val mint = FakeMint()
        val (alice, _) = wallet(mint)
        val (bob, store) = wallet(mint)
        alice.funded(mint, 64)
        val token = alice.send(10)
        mint.dropAfterSwap = true
        assertFailsWith<Exception> { bob.receive(token) }
        assertEquals(1, store.load()!!.pending.size)
        mint.dropAfterSwap = false
        assertEquals(10_000, bob.balanceMsat()) // NUT-09 restore
        assertTrue(store.load()!!.pending.isEmpty())
    }

    @Test
    fun aTokenFromAnotherMintIsRefused() = runBlocking {
        val mint = FakeMint()
        val (w, _) = wallet(mint)
        val foreign = CashuTokens.encodeV4(CashuToken("https://other.mint", listOf(Proof(1, mint.keyset.id, "x", "02aa"))))
        assertFailsWith<IllegalArgumentException> { w.receive(foreign) }
        Unit
    }

    @Test
    fun noKeysend() = runBlocking {
        val (w, _) = wallet(FakeMint())
        val info = w.info()
        assertTrue(info.canPayInvoice)
        assertFalse(info.canKeysend)
        assertEquals("NOT_IMPLEMENTED", assertFailsWith<NwcException> { w.payKeysend(1000, "02", emptyList()) }.code)
    }
}
