package space.sovereigncast.hopp.value

import space.sovereigncast.hopp.crypto.Sha256
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.value.nwc.NwcException
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

/**
 * [wallet], its payments counted as made only with their proof (security audit 2026-10-18, M-02): a
 * 32-byte preimage — for an invoice, the one its payment hash commits to — and fees that are a number
 * of msat, not below zero. Short of that the outcome is unknown: never paid, never failed. Here, not
 * in each implementation, so a wallet added later (WebLN, ADR 0022, 4) cannot leave it out (review
 * 2026-10-31); [WalletRepository.client] hands out only this.
 */
class ProvenWallet(private val wallet: WalletClient) : WalletClient by wallet {
    override suspend fun payInvoice(invoice: String, amountMsat: Long?): PaymentResult {
        // Read before paying: an invoice whose hash cannot be read is not paid on trust.
        val paymentHash = Bolt11.parse(invoice).paymentHash
        return proven(wallet.payInvoice(invoice, amountMsat), paymentHash)
    }

    override suspend fun payKeysend(amountMsat: Long, pubkey: String, tlvRecords: List<TlvRecord>): PaymentResult =
        proven(wallet.payKeysend(amountMsat, pubkey, tlvRecords), null)

    private fun proven(paid: PaymentResult, paymentHash: String?): PaymentResult {
        val preimage = paid.preimage?.lowercase()?.takeIf { HEX_32.matches(it) } ?: throw unknown("no preimage")
        if (paymentHash != null && Sha256.digest(preimage.hexToBytes()).toHex() != paymentHash.lowercase()) throw unknown("the preimage is not the invoice's")
        if (paid.feesPaidMsat != null && paid.feesPaidMsat < 0) throw unknown("fees below zero")
        return paid.copy(preimage = preimage)
    }

    private fun unknown(why: String) = NwcException(NwcException.OUTCOME_UNKNOWN, why)

    private companion object {
        val HEX_32 = Regex("^[0-9a-f]{64}$")
    }
}
