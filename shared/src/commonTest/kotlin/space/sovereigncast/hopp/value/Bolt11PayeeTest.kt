package space.sovereigncast.hopp.value

import space.sovereigncast.hopp.TestVectors
import kotlin.test.Test
import kotlin.test.assertEquals

/** Who an invoice pays — to recognise a payment to oneself (device feedback 2026-10-08).
 *  In common code: the recovery is secp256k1, `@noble/curves` on the web (ADR 0022). */
class Bolt11PayeeTest {
    private val alby = TestVectors.ALBY_INVOICE.trim()

    @Test
    fun thePayeeComesFromTheNFieldOrTheSignature() {
        val parsed = Bolt11.parse(alby)
        assertEquals("02d0fd6c2ec89fbbed6c94bd86e695b3d3086f3ecb7767da3123e519ee36d7bca2", parsed.payeeHex)
        // Most wallets leave `n` out; the signature names the same key.
        assertEquals(parsed.payeeHex, Bolt11.payeeFromSignature(alby))
        assertEquals(21_000L, parsed.amountMsat)
    }
}
