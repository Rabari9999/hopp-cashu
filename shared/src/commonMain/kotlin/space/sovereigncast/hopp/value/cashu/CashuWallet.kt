package space.sovereigncast.hopp.value.cashu

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.sovereigncast.hopp.data.Clock
import space.sovereigncast.hopp.value.Bolt11
import space.sovereigncast.hopp.value.TlvRecord
import space.sovereigncast.hopp.value.WalletClient
import space.sovereigncast.hopp.value.cashu.Coins.message
import space.sovereigncast.hopp.value.nwc.NwcException
import space.sovereigncast.hopp.value.nwc.PaymentResult
import space.sovereigncast.hopp.value.nwc.WalletInfo

/**
 * A Cashu ecash wallet at one mint (ADR 0005, Phase 3), behind the same
 * [WalletClient] as NWC: the router, the budget, the spending lock, the
 * journal and [space.sovereigncast.hopp.value.ProvenWallet]'s preimage check
 * apply unchanged. It pays invoices by melting proofs (NUT-05) and holds no
 * keysend. Custody is the mint's: what the mint does not honour is lost.
 *
 * Every request that spends or creates proofs is journalled first
 * ([PendingOp]); an answer that does not arrive is taken in on the next call
 * by asking the mint (quote state, NUT-07, NUT-09). A melt without an answer
 * is [NwcException.OUTCOME_UNKNOWN], never failed: it may have paid.
 */
class CashuWallet(private val store: ProofStore, private val http: HttpClient, private val clock: Clock) : WalletClient {
    private val mutex = Mutex()
    private val keyCache = mutableMapOf<String, Map<String, String>>()

    private class Session(val client: MintClient, val keysets: List<KeysetInfo>, unit: String) {
        val fees: Map<String, Long> = keysets.associate { it.id to it.inputFeePpk }
        val active: KeysetInfo = keysets.filter { it.active && it.unit == unit }.minByOrNull { it.inputFeePpk }
            ?: throw CashuMintException(null, "no active keyset for $unit", 200)
    }

    private fun state(): EcashState = store.load() ?: throw NwcException("OTHER", "no ecash wallet")

    private suspend fun session(state: EcashState): Session {
        val client = MintClient(http, state.mint)
        return Session(client, client.keysets(), state.unit)
    }

    private suspend fun keys(s: Session, keysetId: String): Map<String, String> = keyCache.getOrPut(keysetId) {
        s.client.keys(s.keysets.firstOrNull { it.id == keysetId } ?: throw CashuMintException(null, "unknown keyset $keysetId", 200))
    }

    override suspend fun info(): WalletInfo =
        WalletInfo(methods = setOf("pay_invoice", "get_balance"), encryptions = emptySet(), alias = state().mint.substringAfter("://"))

    override suspend fun payKeysend(amountMsat: Long, pubkey: String, tlvRecords: List<TlvRecord>): PaymentResult =
        throw NwcException("NOT_IMPLEMENTED", "ecash pays invoices only")

    override suspend fun balanceMsat(): Long = mutex.withLock {
        settleQuietly()
        state().balance * 1000
    }

    override suspend fun payInvoice(invoice: String, amountMsat: Long?): PaymentResult = mutex.withLock {
        settleQuietly()
        val state = state()
        // Nothing has been spent until the melt leaves: a failure before it is a plain failure.
        val s = try { session(state) } catch (e: CancellationException) { throw e } catch (e: Exception) { throw failed(e) }
        val amountless = amountMsat?.takeIf { Bolt11.parse(invoice).amountMsat == null }
        val quote = try { s.client.meltQuote(invoice, state.unit, amountless) } catch (e: CancellationException) { throw e } catch (e: Exception) { throw failed(e) }
        val selection = Coins.select(state.proofs, quote.amount + quote.feeReserve, s.fees)
            ?: throw NwcException("INSUFFICIENT_BALANCE", "${quote.amount + quote.feeReserve} sat and fees needed, ${state.balance} held")
        val keysetId = s.active.id
        val blanks = Coins.prepare(List(Coins.blankCount(selection.total - selection.fee - quote.amount)) { 0L }, keysetId)
        val op = PendingOp(PendingOp.Kind.MELT, blanks, selection.proofs, quote.quote, invoice, clock.now())
        journal(op)

        val answer = try {
            s.client.melt(quote.quote, selection.proofs, blanks.map { it.message() })
        } catch (e: CashuMintException) {
            // A refusal said nothing happened; the quote's state is what proves it.
            val settled = runCatching { settleMelt(s, op) }.getOrNull()
            if (settled?.status == "UNPAID" && op !in state().pending) throw NwcException("PAYMENT_FAILED", e.detail)
            throw unknown("the mint refused, but the payment's state is not known: ${e.detail}")
        } catch (e: CancellationException) {
            // The request may be at the mint: as a dropped connection, not as a cancel (as NwcClient).
            throw unknown("cancelled after sending")
        } catch (e: Exception) {
            throw unknown("no answer from the mint: ${e.message}")
        }
        when (answer.status) {
            "PAID" -> finishMelt(s, op, answer)
            "PENDING" -> throw unknown("the mint is still paying")
            else -> {
                runCatching { releaseInputs(s, op) }
                throw NwcException("PAYMENT_FAILED", "the mint could not pay the invoice")
            }
        }
    }

    /** A Lightning invoice that, once paid, the next call turns into proofs (NUT-04). */
    suspend fun requestInvoice(amountSat: Long): String = mutex.withLock {
        require(amountSat > 0) { "nothing to add" }
        val state = state()
        val s = session(state)
        val quote = s.client.mintQuote(amountSat, state.unit)
        journal(PendingOp(PendingOp.Kind.MINT, Coins.prepare(Coins.split(amountSat), s.active.id), quote = quote.quote, invoice = quote.request, createdAt = clock.now()))
        quote.request
    }

    /** Takes a token's proofs in by swapping them for new ones (NUT-03); the amount received after fees. */
    suspend fun receive(token: String): Long = mutex.withLock {
        val decoded = CashuTokens.decode(token)
        val state = state()
        require(MintClient.normalize(decoded.mint) == state.mint) { "a token from another mint (${decoded.mint})" }
        val s = session(state)
        val amount = decoded.amount - Coins.fee(decoded.proofs, s.fees)
        require(amount > 0) { "the token does not cover the mint's fee" }
        swap(s, decoded.proofs, Coins.split(amount)).sumOf { it.amount }
    }

    /** A token worth exactly [amountSat] to hand to someone; what is left stays here. */
    suspend fun send(amountSat: Long, memo: String? = null): String = mutex.withLock {
        require(amountSat > 0) { "nothing to send" }
        val state = state()
        val s = session(state)
        val selection = Coins.select(state.proofs, amountSat, s.fees)
            ?: throw IllegalArgumentException("$amountSat sat and fees needed, ${state.balance} held")
        val sendAmounts = Coins.split(amountSat)
        val proofs = swap(s, selection.proofs, sendAmounts + Coins.split(selection.total - selection.fee - amountSat))
        val sending = proofs.take(sendAmounts.size)
        store.update { it.copy(proofs = it.proofs - sending.toSet()) }
        CashuTokens.encodeV4(CashuToken(state.mint, sending, state.unit, memo))
    }

    /** Takes in what finished meanwhile: a paid invoice, an answer that was lost. */
    suspend fun refresh(): Unit = mutex.withLock { settle(session(state())) }

    // ---- inside the lock ----

    private fun journal(op: PendingOp) {
        store.update { it.copy(proofs = it.proofs - op.inputs.toSet(), pending = it.pending + op) }
    }

    private fun done(op: PendingOp, add: List<Proof>) {
        store.update { st -> st.copy(proofs = st.proofs + add.filter { p -> st.proofs.none { it.secret == p.secret } }, pending = st.pending - op) }
    }

    /** Swaps [inputs] for outputs of [amounts], journalled; the new proofs, in [amounts]' order, already stored. */
    private suspend fun swap(s: Session, inputs: List<Proof>, amounts: List<Long>): List<Proof> {
        val outputs = Coins.prepare(amounts, s.active.id)
        val op = PendingOp(PendingOp.Kind.SWAP, outputs, inputs, createdAt = clock.now())
        journal(op)
        val signatures = try {
            s.client.swap(inputs, outputs.map { it.message() })
        } catch (e: CashuMintException) {
            runCatching { releaseInputs(s, op) }
            throw e
        }
        return Coins.unblind(outputs, signatures, keys(s, s.active.id)).also { done(op, it) }
    }

    private suspend fun settleQuietly() {
        try {
            val state = store.load() ?: return
            if (state.pending.isNotEmpty()) settle(session(state))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The mint is out of reach: what is pending stays pending, for the next call.
        }
    }

    private suspend fun settle(s: Session) {
        for (op in state().pending) {
            try {
                when (op.kind) {
                    PendingOp.Kind.MELT -> settleMelt(s, op)
                    PendingOp.Kind.MINT -> settleMint(s, op)
                    PendingOp.Kind.SWAP -> if (!restore(s, op)) releaseInputs(s, op)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // one that cannot be settled now does not hold up the others
            }
        }
    }

    private suspend fun settleMelt(s: Session, op: PendingOp): MeltQuote {
        val quote = s.client.meltQuoteState(op.quote!!)
        when (quote.status) {
            "PAID" -> finishMelt(s, op, quote)
            "UNPAID" -> releaseInputs(s, op)
        }
        return quote
    }

    private suspend fun finishMelt(s: Session, op: PendingOp, quote: MeltQuote): PaymentResult {
        val change = op.outputs.firstOrNull()?.let { first ->
            // Change the mint signed wrongly is lost change, not a payment undone.
            runCatching { Coins.unblind(op.outputs, quote.change.orEmpty(), keys(s, first.id)) }.getOrDefault(emptyList())
        }.orEmpty()
        done(op, change)
        val spentSat = op.inputs.sumOf { it.amount } - change.sumOf { it.amount }
        return PaymentResult(quote.paymentPreimage, (spentSat - quote.amount) * 1000)
    }

    private suspend fun settleMint(s: Session, op: PendingOp) {
        val quote = s.client.mintQuoteState(op.quote!!)
        when (quote.status) {
            "PAID" -> done(op, Coins.unblind(op.outputs, s.client.mint(op.quote, op.outputs.map { it.message() }), keys(s, op.outputs.first().id)))
            "ISSUED" -> restore(s, op)
            else -> if (quote.expiry != null && quote.expiry < clock.now()) done(op, emptyList())
        }
    }

    /** NUT-09: proofs for the outputs the mint signed although the answer was lost; false when it signed none. */
    private suspend fun restore(s: Session, op: PendingOp): Boolean {
        if (op.outputs.isEmpty()) return false
        val byBlinded = op.outputs.associateBy { it.blinded }
        val keys = keys(s, op.outputs.first().id)
        val proofs = s.client.restore(op.outputs.map { it.message() }).mapNotNull { (message, signature) ->
            byBlinded[message.b]?.let { out -> runCatching { Coins.unblind(listOf(out), listOf(signature), keys).single() }.getOrNull() }
        }
        if (proofs.isEmpty()) return false
        done(op, proofs)
        return true
    }

    /** The inputs of an operation that did not happen, back in the balance — those the mint says are unspent. */
    private suspend fun releaseInputs(s: Session, op: PendingOp) {
        if (op.inputs.isEmpty()) return done(op, emptyList())
        val states = s.client.checkState(op.inputs.map(Coins::y)).associate { it.y to it.state }
        if (op.inputs.any { states[Coins.y(it)] == "PENDING" }) return
        done(op, op.inputs.filter { states[Coins.y(it)] == "UNSPENT" })
    }

    private fun failed(e: Exception) = NwcException("PAYMENT_FAILED", (e as? CashuMintException)?.detail ?: e.message ?: "mint unreachable")

    private fun unknown(why: String) = NwcException(NwcException.OUTCOME_UNKNOWN, why)

    companion object {
        /** Sets up an empty wallet at [mintUrl]; IllegalArgumentException unless https or onion. */
        fun create(store: ProofStore, mintUrl: String, unit: String = "sat"): EcashState =
            EcashState(MintClient.normalize(mintUrl), unit).also { store.save(it) }
    }
}
