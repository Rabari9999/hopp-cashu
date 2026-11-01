package space.sovereigncast.hopp.value

import kotlinx.coroutines.runBlocking
import space.sovereigncast.hopp.TestVectors
import space.sovereigncast.hopp.value.nwc.NwcException
import space.sovereigncast.hopp.value.nwc.PaymentResult
import space.sovereigncast.hopp.value.nwc.WalletInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Review 2026-10-31: a payment counts as made only with its proof, whatever wallet answered. */
class ProvenWalletTest {
    private class Answers(val result: PaymentResult) : WalletClient {
        override suspend fun info() = WalletInfo(emptySet(), emptySet())
        override suspend fun payInvoice(invoice: String, amountMsat: Long?) = result
        override suspend fun payKeysend(amountMsat: Long, pubkey: String, tlvRecords: List<TlvRecord>) = result
        override suspend fun balanceMsat() = 0L
    }

    private val invoice = TestVectors.ALBY_INVOICE.trim()
    private val somePreimage = "ab".repeat(32)

    private fun unknown(block: suspend () -> Unit) = runBlocking {
        val e = assertFailsWith<NwcException> { block() }
        assertTrue(e.outcomeUnknown, "an unknown outcome, never failed: ${e.code}")
    }

    @Test
    fun anInvoicePaidWithAnotherPreimageIsNotPaid() = unknown {
        ProvenWallet(Answers(PaymentResult(somePreimage, 0))).payInvoice(invoice)
    }

    @Test
    fun anAnswerWithoutAPreimageIsNotPaid() {
        unknown { ProvenWallet(Answers(PaymentResult(null, 0))).payKeysend(1000, "02".padEnd(66, '0'), emptyList()) }
        unknown { ProvenWallet(Answers(PaymentResult("xyz", 0))).payKeysend(1000, "02".padEnd(66, '0'), emptyList()) }
        unknown { ProvenWallet(Answers(PaymentResult(somePreimage, -1))).payKeysend(1000, "02".padEnd(66, '0'), emptyList()) }
    }

    @Test
    fun aKeysendWithAPreimageIsPaid() = runBlocking<Unit> {
        val paid = ProvenWallet(Answers(PaymentResult(somePreimage.uppercase(), 12))).payKeysend(1000, "02".padEnd(66, '0'), emptyList())
        assertEquals(PaymentResult(somePreimage, 12), paid)
    }
}
