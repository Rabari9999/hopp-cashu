package space.sovereigncast.hopp.value.cashu

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.sovereigncast.hopp.platform.SecretStore

/**
 * An output the wallet made before asking the mint to sign it: what it needs
 * to turn the mint's blind signature into a proof (NUT-00), kept until then.
 * [amount] 0 is a blank output for fee change (NUT-08), the mint sets its value.
 */
@Serializable
data class PreparedOutput(val amount: Long, val id: String, val secret: String, val r: String, val blinded: String)

/**
 * An operation sent to the mint whose answer has not been taken in yet —
 * written before the request leaves (as the NWC journal is, ADR 0005), so a
 * crash or a dropped connection cannot lose money that is in flight:
 * [inputs] are no longer in the balance but not known spent, [outputs] are
 * what the answer would be unblinded with.
 */
@Serializable
data class PendingOp(
    val kind: Kind,
    val outputs: List<PreparedOutput>,
    val inputs: List<Proof> = emptyList(),
    val quote: String? = null,
    val invoice: String? = null,
    val createdAt: Long,
) {
    enum class Kind { MINT, SWAP, MELT }
}

/** The ecash wallet at one mint: spendable [proofs] and operations in flight. */
@Serializable
data class EcashState(
    val mint: String,
    val unit: String = "sat",
    val proofs: List<Proof> = emptyList(),
    val pending: List<PendingOp> = emptyList(),
) {
    val balance: Long get() = proofs.sumOf { it.amount }
}

/**
 * Proofs are bearer money, so they live where the NWC string does: the
 * [SecretStore] (Keystore / Keychain), never the database or a backup
 * (ADR 0015). One entry for the whole state, so moving proofs into a pending
 * operation is a single write — never half done.
 *
 * Not synchronized: the wallet serializes its operations.
 */
class ProofStore(private val secrets: SecretStore) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** null when no ecash wallet is set up; throws SecretUnreadable as the store does. */
    fun load(): EcashState? = secrets.get(KEY)?.let { json.decodeFromString(EcashState.serializer(), it) }

    fun save(state: EcashState?) = secrets.put(KEY, state?.let { json.encodeToString(EcashState.serializer(), it) })

    /** Applies [change] to the stored state and stores the result; throws when there is none. */
    fun update(change: (EcashState) -> EcashState): EcashState {
        val state = load() ?: throw IllegalStateException("no ecash wallet")
        return change(state).also { save(it) }
    }

    companion object {
        const val KEY = "cashu_wallet"
    }
}
