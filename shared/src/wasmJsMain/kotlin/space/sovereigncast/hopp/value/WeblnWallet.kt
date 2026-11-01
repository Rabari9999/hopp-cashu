package space.sovereigncast.hopp.value

import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.sovereigncast.hopp.crypto.hexToBytes
import space.sovereigncast.hopp.crypto.toHex
import space.sovereigncast.hopp.value.nwc.NwcException
import space.sovereigncast.hopp.value.nwc.PaymentResult
import space.sovereigncast.hopp.value.nwc.WalletInfo
import kotlin.js.Promise

// Each call settles with JSON, its failure as {"error": "…"}: a JS rejection is no kotlin.Exception
// (docs/spikes/06-web.md, 3), and its message is what the listener should read.
private fun hasWebln(): Boolean = js("typeof window !== 'undefined' && !!window.webln")

private fun weblnEnable(): Promise<JsString> = js("""
  Promise.resolve().then(() => window.webln.enable())
    .then(() => window.webln.getInfo ? window.webln.getInfo().catch(() => null) : null)
    .then(i => JSON.stringify({
      alias: (i && i.node && i.node.alias) || null,
      pubkey: (i && i.node && i.node.pubkey) || null,
      methods: (i && Array.isArray(i.methods)) ? i.methods : null,
      functions: ['sendPayment', 'keysend', 'getBalance'].filter(m => typeof window.webln[m] === 'function'),
    }))
    .catch(e => JSON.stringify({ error: String((e && e.message) || e) }))
""")

private fun weblnSendPayment(invoice: String): Promise<JsString> = js("""
  Promise.resolve().then(() => window.webln.sendPayment(invoice))
    .then(r => JSON.stringify({ preimage: (r && r.preimage) || null, feesSat: (r && r.route && typeof r.route.total_fees === 'number') ? r.route.total_fees : null }))
    .catch(e => JSON.stringify({ error: String((e && e.message) || e) }))
""")

private fun weblnKeysend(destination: String, sats: String, records: String): Promise<JsString> = js("""
  Promise.resolve().then(() => window.webln.keysend({ destination: destination, amount: sats, customRecords: JSON.parse(records) }))
    .then(r => JSON.stringify({ preimage: (r && r.preimage) || null }))
    .catch(e => JSON.stringify({ error: String((e && e.message) || e) }))
""")

private fun weblnBalance(): Promise<JsString> = js("""
  Promise.resolve().then(() => window.webln.getBalance())
    .then(r => JSON.stringify({ balance: (r && typeof r.balance === 'number') ? r.balance : null, currency: (r && r.currency) || null }))
    .catch(e => JSON.stringify({ error: String((e && e.message) || e) }))
""")

/**
 * WebLN (ADR 0022, 4, stage W3): the wallet of a browser extension such as
 * Alby, as Hopp's [BrowserWallet]. The extension shows each payment it does
 * not cover with its own budget to the listener; Hopp sees only the answer.
 *
 * WebLN pays in whole sats — every share Hopp sends is one ([Splits.divideAtWholeSats]);
 * keysend's records travel as text, which bLIP-10's boostagram and the
 * recipients' custom values are.
 */
class WeblnWallet : BrowserWallet, WalletClient {
    private val json = Json { ignoreUnknownKeys = true }
    private var enabled = false

    @Serializable
    private data class Enabled(val alias: String? = null, val pubkey: String? = null, val methods: List<String>? = null, val functions: List<String> = emptyList(), val error: String? = null)

    @Serializable
    private data class Paid(val preimage: String? = null, val feesSat: Long? = null, val error: String? = null)

    @Serializable
    private data class Balance(val balance: Long? = null, val currency: String? = null, val error: String? = null)

    override fun available(): Boolean = hasWebln()

    override suspend fun enable(): ConnectedWallet {
        val e = call<Enabled> { weblnEnable() }
        e.error?.let { throw NwcException(REFUSED, it) }
        enabled = true
        // What getInfo names, where it does; else what the extension has at all.
        val names = e.methods ?: e.functions
        val methods = buildSet {
            if ("sendPayment" in names) add("pay_invoice")
            if ("keysend" in names) add("pay_keysend")
            if ("getBalance" in names) add("get_balance")
        }
        return ConnectedWallet(alias = e.alias, methods = methods, pubkey = e.pubkey, browser = true)
    }

    override fun client(): WalletClient = this

    override suspend fun info(): WalletInfo = enable().info

    override suspend fun payInvoice(invoice: String, amountMsat: Long?): PaymentResult {
        // WebLN pays only what the invoice says; Hopp asks every invoice for its amount.
        if (amountMsat != null) throw NwcException(NOT_SENT, "the browser's wallet cannot choose an invoice's amount")
        ensureEnabled()
        val paid = call<Paid> { weblnSendPayment(invoice) }
        return answer(paid)
    }

    override suspend fun payKeysend(amountMsat: Long, pubkey: String, tlvRecords: List<TlvRecord>): PaymentResult {
        if (amountMsat <= 0 || amountMsat % 1000 != 0L) throw NwcException(NOT_SENT, "the browser's wallet pays whole sats only")
        val records = JsonObject(tlvRecords.associate { r ->
            val text = r.valueHex.hexToBytes().decodeToString()
            // Text only: a value that is not would arrive changed, and is not sent at all.
            if (text.encodeToByteArray().toHex() != r.valueHex.lowercase()) throw NwcException(NOT_SENT, "record ${r.type} is not text")
            r.type.toString() to JsonPrimitive(text)
        })
        ensureEnabled()
        val paid = call<Paid> { weblnKeysend(pubkey, (amountMsat / 1000).toString(), records.toString()) }
        return answer(paid)
    }

    override suspend fun balanceMsat(): Long {
        ensureEnabled()
        val b = call<Balance> { weblnBalance() }
        b.error?.let { throw NwcException(REFUSED, it) }
        val sats = b.balance ?: throw NwcException("BAD_RESPONSE", "no balance")
        if (b.currency != null && !b.currency.equals("sats", ignoreCase = true) && !b.currency.equals("sat", ignoreCase = true)) throw NwcException("BAD_RESPONSE", "a balance in ${b.currency}")
        return sats * 1000
    }

    /** After a reload the extension wants to be asked again before it pays. */
    private suspend fun ensureEnabled() {
        if (!hasWebln()) throw NwcException(NOT_SENT, "the browser's wallet is gone")
        if (!enabled) enable()
    }

    /**
     * A refusal is said by the extension before it pays — the listener's "no",
     * a payment the node could not route. Only a wait that ran out may have
     * paid: such an answer is the outcome unknown, never paid again (H-04).
     */
    private fun answer(paid: Paid): PaymentResult {
        paid.error?.let { error ->
            val unknown = TIMED_OUT.containsMatchIn(error)
            throw NwcException(if (unknown) NwcException.OUTCOME_UNKNOWN else REFUSED, error)
        }
        return PaymentResult(paid.preimage, paid.feesSat?.let { it * 1000 })
    }

    private suspend inline fun <reified T> call(block: () -> Promise<JsString>): T =
        json.decodeFromString<T>(block().await<JsString>().toString())

    private companion object {
        /** Not paid: refused by the extension or the listener, or failed before it went out. */
        const val REFUSED = "WEBLN_REFUSED"
        /** Not sent at all: Hopp did not ask the extension. */
        const val NOT_SENT = "WEBLN_NOT_SENT"
        val TIMED_OUT = Regex("time ?d? ?out|timeout|timed out", RegexOption.IGNORE_CASE)
    }
}
