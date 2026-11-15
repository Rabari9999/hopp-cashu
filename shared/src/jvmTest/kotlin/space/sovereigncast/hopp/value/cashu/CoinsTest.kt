package space.sovereigncast.hopp.value.cashu

import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.platform.MemorySecretStore
import space.sovereigncast.hopp.platform.SecretUnreadable
import space.sovereigncast.hopp.value.cashu.Coins.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoinsTest {
    private fun proof(amount: Long, id: String = "00aa", n: Int = 0) = Proof(amount, id, "s$amount-$n", "02")

    @Test
    fun splitIntoPowersOfTwo() {
        assertEquals(listOf(1L, 4L, 8L), Coins.split(13))
        assertEquals(emptyList(), Coins.split(0))
        assertEquals(listOf(1L shl 62), Coins.split(1L shl 62))
    }

    @Test
    fun feeIsPerInputRoundedUp() {
        val fees = mapOf("00aa" to 100L, "00bb" to 0L)
        assertEquals(0, Coins.fee(emptyList(), fees))
        assertEquals(1, Coins.fee(listOf(proof(1)), fees))
        assertEquals(1, Coins.fee(List(10) { proof(1, n = it) }, fees))
        assertEquals(2, Coins.fee(List(11) { proof(1, n = it) }, fees))
        assertEquals(0, Coins.fee(listOf(proof(1, "00bb")), fees))
    }

    @Test
    fun selectCoversAmountAndItsOwnFee() {
        val wallet = listOf(proof(1), proof(2), proof(4), proof(8), proof(16))
        val noFee = Coins.select(wallet, 5, emptyMap())!!
        assertEquals(listOf(1L, 4L), noFee.proofs.map { it.amount }.sorted()) // exact, no change
        assertEquals(0, noFee.fee)
        // 8 alone leaves nothing for its 1-sat fee; 8+2+1 would pay 3 in fees, one 16 pays 1
        val withFee = Coins.select(wallet, 8, mapOf("00aa" to 1000L))!!
        assertTrue(withFee.total >= 8 + withFee.fee)
        assertEquals(1, withFee.proofs.size)
        assertEquals(16, withFee.total)
        assertEquals(31, Coins.select(wallet, 31, emptyMap())!!.total)
        assertNull(Coins.select(wallet, 31, mapOf("00aa" to 1L)))
        assertNull(Coins.select(wallet, 32, emptyMap()))
    }

    @Test
    fun blankOutputsForFeeChange() {
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 10), listOf(0L, 1L, 2L, 3L, 4L, 5L, 1000L).map(Coins::blankCount))
    }

    @Test
    fun preparedOutputsUnblindToProofsWithCheckedDleq() {
        val mint = FakeMintKeys()
        val outputs = Coins.prepare(listOf(1L, 4L, 8L), mint.id)
        assertEquals(3, outputs.map { it.secret }.toSet().size)
        val proofs = Coins.unblind(outputs, outputs.map { mint.sign(it.message()) }, mint.keys)
        assertEquals(listOf(1L, 4L, 8L), proofs.map { it.amount })
        for (p in proofs) {
            val key = mint.keys.getValue(p.amount.toString()).hexToBytes()
            assertTrue(Bdhke.verifyProofDleq(p.secret.encodeToByteArray(), p.c.hexToBytes(), p.dleq!!.e.hexToBytes(), p.dleq!!.s.hexToBytes(), p.dleq!!.r.hexToBytes(), key))
        }
        assertTrue(proofs.all(mint::accepts)) // C = k·Y: the mint takes them back
    }

    @Test
    fun aSignatureThatIsNotWhatWasAskedIsRefused() {
        val mint = FakeMintKeys()
        val other = FakeMintKeys("other mint")
        val out = Coins.prepare(listOf(4L), mint.id)
        assertFailsWith<IllegalArgumentException> { Coins.unblind(out, listOf(mint.sign(out[0].message(), badDleq = true)), mint.keys) }
        assertFailsWith<IllegalArgumentException> { Coins.unblind(out, listOf(mint.sign(out[0].message(), amount = 8)), mint.keys) }
        assertFailsWith<IllegalArgumentException> { Coins.unblind(out, listOf(other.sign(out[0].message())), mint.keys) }
        assertFailsWith<IllegalArgumentException> { Coins.unblind(out, List(2) { mint.sign(out[0].message()) }, mint.keys) }
    }

    @Test
    fun blankOutputsTakeTheAmountTheMintGives() {
        val mint = FakeMintKeys()
        val blanks = Coins.prepare(List(Coins.blankCount(10)) { 0L }, mint.id)
        assertEquals(4, blanks.size)
        assertEquals(1L, blanks[0].message().amount)
        val change = Coins.unblind(blanks, listOf(mint.sign(blanks[0].message(), amount = 2), mint.sign(blanks[1].message(), amount = 4)), mint.keys)
        assertEquals(listOf(2L, 4L), change.map { it.amount })
    }

    @Test
    fun storeKeepsTheWholeStateInOneSecret() {
        val secrets = MemorySecretStore()
        val store = ProofStore(secrets)
        assertNull(store.load())
        assertFailsWith<IllegalStateException> { store.update { it } }
        store.save(EcashState("https://mint.test"))
        val op = PendingOp(PendingOp.Kind.MELT, Coins.prepare(listOf(0L), "00aa"), listOf(proof(8)), "q1", "lnbc1", 1)
        store.update { it.copy(proofs = listOf(proof(1), proof(2)), pending = listOf(op)) }
        val back = store.load()!!
        assertEquals(3, back.balance)
        assertEquals(op, back.pending.single())
        secrets.unreadable += ProofStore.KEY
        assertFailsWith<SecretUnreadable> { store.load() }
        secrets.unreadable.clear()
        store.save(null)
        assertNull(store.load())
    }

    @Test
    fun yIsHashToCurveOfTheSecretString() {
        assertEquals(Bdhke.hashToCurve("abc".encodeToByteArray()).toHex(), Coins.y(Proof(1, "00", "abc", "02")))
    }
}
