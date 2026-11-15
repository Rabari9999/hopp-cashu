package space.sovereigncast.hopp.value.cashu

import space.sovereigncast.hopp.crypto.Secp256k1
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.secureRandomBytes
import space.sovereigncast.hopp.crypto.toHex

/** Proofs chosen to pay an amount and the input fee the mint charges for them. */
data class Selection(val proofs: List<Proof>, val fee: Long) {
    val total: Long get() = proofs.sumOf { it.amount }
}

/** Amounts, fees and outputs: the arithmetic of a Cashu wallet. */
internal object Coins {
    /** [amount] as powers of two, smallest first — the mint has a key for each. */
    fun split(amount: Long): List<Long> {
        require(amount >= 0) { "negative amount" }
        return (0 until 63).map { 1L shl it }.filter { amount and it != 0L }
    }

    /** NUT-02: the sum of each input's keyset fee, per thousand, rounded up. */
    fun fee(proofs: List<Proof>, feePpk: Map<String, Long>): Long {
        val ppk = proofs.sumOf { feePpk[it.id] ?: 0L }
        return (ppk + 999) / 1000
    }

    /**
     * Proofs worth at least [amount] plus their own input fee, or null when
     * the balance cannot cover both. Of three candidates — powers of two
     * that land on the amount without going over, the smallest single proof
     * that covers it, the largest first — the one with the least fee, then
     * the least to pay back as change. Each is trimmed of proofs it can do
     * without.
     */
    fun select(proofs: List<Proof>, amount: Long, feePpk: Map<String, Long>): Selection? {
        require(amount > 0) { "nothing to pay" }
        fun covers(c: List<Proof>) = c.sumOf { it.amount } >= amount + fee(c, feePpk)
        val descending = proofs.sortedByDescending { it.amount }

        val exact = mutableListOf<Proof>()
        for (p in descending) if (exact.sumOf { it.amount } + p.amount <= amount + fee(exact + p, feePpk)) exact += p
        if (!covers(exact)) (proofs - exact.toSet()).sortedBy { it.amount }.firstOrNull { covers(exact + it) }?.let { exact += it }

        val single = descending.lastOrNull { covers(listOf(it)) }?.let { mutableListOf(it) }

        val largest = mutableListOf<Proof>()
        for (p in descending) { if (covers(largest)) break; largest += p }

        return listOfNotNull(exact, single, largest)
            .filter(::covers)
            .map { c -> c.apply { for (p in sortedBy { it.amount }) if (covers(this - p)) remove(p) } }
            .map { Selection(it.toList(), fee(it, feePpk)) }
            .minWithOrNull(compareBy({ it.fee }, { it.total }))
    }

    /** NUT-08: blank outputs enough to take any change of [feeReserve] back. */
    fun blankCount(feeReserve: Long): Int =
        if (feeReserve <= 0) 0 else maxOf(1, 64 - (feeReserve - 1).countLeadingZeroBits())

    /**
     * Fresh outputs for [amounts] on [keysetId]: a random 32-byte secret (hex,
     * as other wallets make them) and a random blinding factor each. Amount 0
     * makes a blank output.
     */
    fun prepare(amounts: List<Long>, keysetId: String): List<PreparedOutput> = amounts.map { amount ->
        val secret = secureRandomBytes(32).toHex()
        val r = randomScalar()
        PreparedOutput(amount, keysetId, secret, r.toHex(), Bdhke.blind(secret.encodeToByteArray(), r).toHex())
    }

    fun PreparedOutput.message(): BlindedMessage = BlindedMessage(if (amount == 0L) 1 else amount, id, blinded)

    /**
     * The mint's signatures, in output order, as proofs. A signature on another
     * keyset or amount than asked, a key the wallet does not know, or a DLEQ
     * proof that does not verify (NUT-12) is refused: proofs the mint would
     * not take back are worth nothing. Fewer signatures than outputs is fine
     * for melt change, which only fills the first blanks.
     */
    fun unblind(outputs: List<PreparedOutput>, signatures: List<BlindSignature>, keys: Map<String, String>): List<Proof> {
        require(signatures.size <= outputs.size) { "more signatures than outputs" }
        return signatures.zip(outputs) { sig, out ->
            require(sig.id == out.id) { "signature on keyset ${sig.id}, asked ${out.id}" }
            require(out.amount == 0L || sig.amount == out.amount) { "signature for ${sig.amount}, asked ${out.amount}" }
            require(sig.amount > 0) { "signature for ${sig.amount}" }
            val key = keys[sig.amount.toString()]?.hexToBytes() ?: throw IllegalArgumentException("no mint key for ${sig.amount}")
            val blindSig = sig.c.hexToBytes()
            val r = out.r.hexToBytes()
            val dleq = sig.dleq?.let {
                require(Bdhke.verifyDleq(it.e.hexToBytes(), it.s.hexToBytes(), key, out.blinded.hexToBytes(), blindSig)) { "bad DLEQ from mint" }
                ProofDleq(it.e, it.s, out.r)
            }
            Proof(sig.amount, sig.id, out.secret, Bdhke.unblind(blindSig, r, key).toHex(), dleq)
        }
    }

    /** Y of each proof, for NUT-07's check of what is spent. */
    fun y(proof: Proof): String = Bdhke.hashToCurve(proof.secret.encodeToByteArray()).toHex()

    private fun randomScalar(): ByteArray {
        while (true) {
            val r = secureRandomBytes(32)
            if (Secp256k1.secKeyVerify(r)) return r
        }
    }
}
