package space.sovereigncast.hopp.value

import space.sovereigncast.hopp.value.nwc.PaymentResult
import space.sovereigncast.hopp.value.nwc.WalletInfo

/**
 * What pays for the listener (ADR 0005): their own wallet, never one of Hopp's.
 * In the apps it is Nostr Wallet Connect ([space.sovereigncast.hopp.value.nwc.NwcClient]);
 * in the browser WebLN joins it (ADR 0022, 4, stage W3). Boosts, streaming and
 * Sovereigncast payments speak only to this.
 */
interface WalletClient {
    /** What the wallet can: its methods and how it talks. */
    suspend fun info(): WalletInfo

    /** Pays a BOLT 11 invoice; [amountMsat] only for one without an amount. Proven by a preimage, or it throws. */
    suspend fun payInvoice(invoice: String, amountMsat: Long? = null): PaymentResult

    /** Pays [pubkey] directly, the boostagram in [tlvRecords] (bLIP-10). */
    suspend fun payKeysend(amountMsat: Long, pubkey: String, tlvRecords: List<TlvRecord>): PaymentResult

    /** What the wallet holds, in msat; throws when it does not tell. */
    suspend fun balanceMsat(): Long
}
